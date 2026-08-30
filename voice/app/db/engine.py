"""DB 接続とテナント境界。

★ このサービスはマイグレーションを持たない。スキーマの所有者は api（Flyway）。
  2 箇所から schema を変えると「片方だけ適用された状態」が生まれ、
  どちらが正しいか分からなくなる。

★ 業務データに触る経路は tenant_tx() だけにしてある。
  プールから素の接続を borrow する関数を公開しない。公開すると、
  新しい機能を足したときに SET LOCAL を書き忘れ、
  「0 行返る」か「他テナントが見える」のどちらかになる。
"""

from __future__ import annotations

import asyncio
import contextlib
import re
from collections.abc import AsyncIterator
from uuid import UUID

import asyncpg

from .. import logger
from ..config import APP_ENV, DATABASE_URL

_pool: asyncpg.Pool | None = None


class RlsNotEnforced(RuntimeError):
    """接続ロールが RLS を素通りする状態。production では起動を止める。"""


class DatabaseCredentialsRejected(RuntimeError):
    """DATABASE_URL の資格情報が DB に拒否された。再試行しても直らない。"""


# ★ 一時的な接続失敗（DB の再起動・ネットワークの瞬断）は待てば直る。
#   最大でこの回数まで、指数的に間隔を空けて待つ。
_CONNECT_ATTEMPTS = 5
_CONNECT_BACKOFF_CAP_SECONDS = 30


async def init_pool() -> None:
    """DB プールを用意する。

    ★ 「待てば直る失敗」と「待っても直らない失敗」を分ける。
      一時的な接続失敗は間隔を空けて再試行する。パスワード違いのような
      設定の誤りは即座に諦め、何を直せばよいかを 1 行で伝える。

    ★ 設定の誤りで生の traceback を出さない。以前はここで
      asyncpg の 40 行のスタックがそのまま出て、しかもコンテナが
      1.3 秒ごとに再起動していた。ログは埋まり、PostgreSQL 側には
      認証失敗が秒間 1 回ずつ記録され続け、肝心の
      「どの変数を直せばよいか」はどこにも書かれていなかった。
    """
    global _pool
    if _pool is not None:
        return

    for attempt in range(1, _CONNECT_ATTEMPTS + 1):
        try:
            _pool = await asyncpg.create_pool(
                dsn=DATABASE_URL,
                min_size=1,
                max_size=10,
                # ★ 文の準備をキャッシュしない。pgbouncer の transaction pooling の
                #   後ろに置かれたときに、準備済み文が別の接続で使われて壊れる
                statement_cache_size=0,
            )
            break

        except asyncpg.InvalidAuthorizationSpecificationError as e:
            # ★ 待っても直らない。パスワードかロール名が実際の DB と食い違っている。
            #   資格情報は DATABASE_URL（各サービス）と DB のロードの
            #   2 か所にあり、片方だけ変えるとここに出る
            raise DatabaseCredentialsRejected(
                f"DATABASE_URL の資格情報が DB に拒否されました（{_describe_dsn()}）。"
                "DB 側のロードのパスワードを変えたなら、それを使う"
                "すべてのサービスの DATABASE_URL も同時に更新してください"
            ) from e

        except asyncpg.InvalidCatalogNameError as e:
            raise DatabaseCredentialsRejected(
                f"DATABASE_URL のデータベースが存在しません（{_describe_dsn()}）"
            ) from e

        except (OSError, asyncpg.CannotConnectNowError) as e:
            # ★ 待てば直る類。DB の再起動中やネットワークの瞬断
            if attempt == _CONNECT_ATTEMPTS:
                raise
            delay = min(2**attempt, _CONNECT_BACKOFF_CAP_SECONDS)
            logger.warn(
                "DB に接続できません。待って再試行します",
                attempt=attempt,
                of=_CONNECT_ATTEMPTS,
                retry_in_seconds=delay,
                error=str(e),
            )
            await asyncio.sleep(delay)

    async with _pool.acquire() as conn:
        await conn.fetchval("select 1")
        await assert_rls_enforced(conn)
    logger.info("voice: DB プールを初期化しました")


def _describe_dsn() -> str:
    """接続先をログに出せる形にする。

    ★ パスワードは絶対に含めない。ログは共有されるし長く残る。
      ロール名とホストだけあれば、どの変数を直せばよいかは分かる。
    """
    m = re.match(r"postgres(?:ql)?://([^:/?#]+)(?::[^@]*)?@([^/?#]+)", DATABASE_URL or "")
    if not m:
        return "DATABASE_URL の形式が不正です"
    return f"role={m.group(1)} host={m.group(2)}"


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def pool() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("DB プールが初期化されていません")
    return _pool


async def assert_rls_enforced(conn: asyncpg.Connection) -> None:
    """接続ロールで RLS が効くことを確かめる。

    ★ force row level security はテーブル所有者には効くが、
      **superuser と BYPASSRLS ロールには効かない**。

      マネージド Postgres（RDS / Aurora / Railway 等）が配る既定の接続ユーザーは
      たいていそのどちらかで、そのまま繋ぐとポリシーは書かれているのに
      1 行も効かない。テナント分離だけが本番で失われ、しかもアプリは
      正常に動くので気付けない。

    ★ api（Spring Boot）側にも同じ検査を置いてある。片方だけ守っても、
      もう片方から漏れる。
    """
    row = await conn.fetchrow(
        "select rolsuper, rolbypassrls from pg_roles where rolname = current_user"
    )
    if row is None:
        return
    if not (row["rolsuper"] or row["rolbypassrls"]):
        return

    reason = "superuser" if row["rolsuper"] else "BYPASSRLS"
    message = (
        f"アプリの接続ロールが {reason} のため、RLS が適用されません。"
        "テナント分離が無効の状態です"
    )
    hint = (
        "db/bootstrap-roles.sql を流して kaden_app / kaden_migrator を作り、"
        "DATABASE_URL を kaden_app に向けてください"
    )

    if APP_ENV == "production":
        logger.error(f"{message} — {hint}")
        raise RlsNotEnforced(f"{message}。{hint}")

    # 開発中は止めない。ローカルで migrator を使って動かすことがある
    logger.warn(f"{message} — {hint}（APP_ENV={APP_ENV} のため続行）")


@contextlib.asynccontextmanager
async def tenant_tx(tenant_id: UUID | str) -> AsyncIterator[asyncpg.Connection]:
    """テナントを設定したトランザクションを開く。

    ★ 業務データに触るときは必ずこれを通す。
      set_config(..., true) は SET LOCAL と同義で、トランザクション終了時に
      自動で戻る。接続はプールで使い回されるので、トランザクションを越えて
      値が残る形（SET や接続初期化フック）にしてはいけない。残ると、
      返却された接続を拾った別の処理が前のテナントとして動く。
    """
    async with pool().acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "select set_config('app.tenant_id', $1, true)", str(tenant_id)
            )
            yield conn


@contextlib.asynccontextmanager
async def system_tx() -> AsyncIterator[asyncpg.Connection]:
    """テナントに属さないデータ用（webhook_events など）。

    ★ tenant_id を設定しないので、RLS が有効なテーブルには 1 行も触れない。
      これは制限ではなく仕様。署名検証の前段など、まだテナントが
      確定していない処理はここを使い、確定したら tenant_tx に移る。
    """
    async with pool().acquire() as conn:
        async with conn.transaction():
            yield conn
