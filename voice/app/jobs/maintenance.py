"""定期ジョブ。

★ これを動かさないと、静かに壊れる。
    - 録音が Twilio 上に置かれたまま自前の保管先に来ない
      （Twilio 側の保持期間を過ぎると消える）
    - 保存期限を過ぎた録音が消えず、個人情報が溜まり続ける
    - AI 分析が pending のまま残り、画面に何も出ない
  どれも「エラーが出ない」ので、動いていないことに気付きにくい。
  最後に成功した時刻をログに出し、監視から見えるようにする。

★ 1 サイクルの中で 1 つが落ちても他は動かす。録音の取得で例外が出たら
  削除も分析も止まる、という作りにしない。

★ 冪等にする。同じ行を 2 回処理しても壊れないこと。
  ジョブは落ちて再起動するのが前提。

★ 1 件ずつ順番に処理しない。どの処理も待ち時間の塊で、CPU はほぼ使わない。

    録音の取得   Twilio からのダウンロード（数 MB） + S3 への保存
    分析         LLM の応答待ち（数秒〜十数秒）

  順番にやると、1 サイクルの所要時間が「件数 × 1 件の待ち時間」になる。
  分析が 1 件 8 秒なら 20 件で 160 秒。--loop 60 で回していても実際は
  160 秒に 1 周しかせず、溜まる速さのほうが速ければ差は開き続ける。
  しかも待ち行列が伸びていることはログの件数からは分からない
  （毎回きっちり 20 件処理できてしまうため）。

  待ちは重ねられるので、同時実行数を決めて並べる。上限を置くのは、
  外部 API のレート制限と、S3 への帯域を食い尽くさないため。

★ 積み残しをログに出す。「20 件処理した」だけだと、
  それが「20 件しか無かった」のか「上限で切った」のかが分からない。

起動:
    python -m app.jobs.maintenance --loop 60
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import time
from datetime import UTC, datetime

import httpx

from .. import logger, storage
from ..ai.analyze import AnalysisError, analyze
from ..config import (
    JOB_INTERVAL_SECONDS,
    TELEPHONY_ENABLED,
    TWILIO_ACCOUNT_SID,
    TWILIO_AUTH_TOKEN,
)
from ..db.engine import (
    DatabaseCredentialsRejected,
    close_pool,
    init_pool,
    system_tx,
    tenant_tx,
)

# 1 サイクルで扱う件数。多すぎると 1 回のサイクルが長引き、
# 少なすぎると溜まる。実測して調整する前提の初期値
BATCH_SIZE = 20
# ★ 何度も失敗する行を無限に試さない。人が見るべき状態にする
MAX_ATTEMPTS = 5

# ★ 同時実行数。待ち時間を重ねるためのもので、CPU の数とは関係ない。
#   録音は帯域（1 件数 MB）、分析は LLM のレート制限が上限を決める。
RECORDING_CONCURRENCY = 4
ANALYSIS_CONCURRENCY = 4

# ★ 接続を使い回す。1 件ごとに AsyncClient を作ると、そのたびに
#   TCP と TLS の確立が入る。同じホストへ続けて取りに行くので、
#   使い回すだけで 1 件あたり数十〜数百ミリ秒変わる。
_http: httpx.AsyncClient | None = None


def _client() -> httpx.AsyncClient:
    global _http
    if _http is None:
        _http = httpx.AsyncClient(
            timeout=60.0,
            # ★ 同時実行数に合わせる。少ないと自分で自分を待たせる
            limits=httpx.Limits(max_connections=RECORDING_CONCURRENCY,
                                max_keepalive_connections=RECORDING_CONCURRENCY),
        )
    return _http


async def _close_client() -> None:
    global _http
    if _http is not None:
        await _http.aclose()
        _http = None


async def _bounded(limit: int, coros) -> list:
    """同時実行数を絞って並行に走らせる。例外は呼び出し側で扱う。"""
    sem = asyncio.Semaphore(limit)

    async def run(c):
        async with sem:
            return await c

    return await asyncio.gather(*(run(c) for c in coros), return_exceptions=True)


# ---------------------------------------------------------------- 録音の取得

async def fetch_recordings() -> int:
    """Twilio の録音を自前の保管先へ移す。

    ★ webhook の中でダウンロードしない。数 MB の転送を
      Twilio のタイムアウト内で終える必要が出てしまう。
    """
    if not TELEPHONY_ENABLED:
        return 0

    async with system_tx() as conn:
        rows = await conn.fetch(
            """
            select r.id, r.tenant_id, r.call_session_id, r.provider_recording_sid
              from recordings r
             where r.status in ('pending', 'failed')
               and r.attempts < $1
             order by r.created_at
             limit $2
            """,
            MAX_ATTEMPTS,
            BATCH_SIZE,
        )

    # ★ 1 件の失敗で全体を止めない。並行にしても同じで、
    #   例外は行ごとに受け止めて failed に倒す
    results = await _bounded(
        RECORDING_CONCURRENCY, [_fetch_one(row) for row in rows]
    )

    moved = 0
    for row, result in zip(rows, results, strict=True):
        if isinstance(result, BaseException):
            await _mark_recording_failed(row["tenant_id"], row["id"], str(result))
            logger.warn(
                "録音の取得に失敗しました",
                recording_id=str(row["id"]),
                error=str(result),
            )
        else:
            moved += 1

    if len(rows) == BATCH_SIZE:
        # ★ 上限で切った可能性がある。黙って切ると「毎回きっちり 20 件」の
        #   ログが並ぶだけで、溜まっていることに気付けない
        logger.warn("録音の取得が上限に達しました（積み残しの可能性）", batch=BATCH_SIZE)
    return moved


async def _fetch_one(row) -> None:
    sid = row["provider_recording_sid"]
    url = (
        f"https://api.twilio.com/2010-04-01/Accounts/{TWILIO_ACCOUNT_SID}"
        f"/Recordings/{sid}.wav"
    )
    auth = base64.b64encode(
        f"{TWILIO_ACCOUNT_SID}:{TWILIO_AUTH_TOKEN}".encode()
    ).decode()

    response = await _client().get(url, headers={"Authorization": f"Basic {auth}"})
    response.raise_for_status()
    audio = response.content

    key = storage.object_key(
        str(row["tenant_id"]), str(row["call_session_id"]), sid
    )
    # ★ boto3 は同期。ここで直接呼ぶと数 MB の転送のあいだ
    #   イベントループが止まり、並行にした意味が無くなる
    stored = await storage.put_async(key, audio)

    async with tenant_tx(row["tenant_id"]) as conn:
        await conn.execute(
            """
            update recordings
               set status = 'stored',
                   storage_bucket = $2,
                   storage_key = $3,
                   size_bytes = $4,
                   attempts = attempts + 1,
                   last_error = null,
                   updated_at = now()
             where id = $1
            """,
            row["id"],
            stored.bucket,
            stored.key,
            stored.size_bytes,
        )

    logger.info("録音を保管しました", recording_id=str(row["id"]), bytes=stored.size_bytes)


async def _mark_recording_failed(tenant_id, recording_id, error: str) -> None:
    async with tenant_tx(tenant_id) as conn:
        await conn.execute(
            """
            update recordings
               set status = 'failed', attempts = attempts + 1,
                   last_error = $2, updated_at = now()
             where id = $1
            """,
            recording_id,
            error[:500],
        )


# ---------------------------------------------------------------- 保存期限

async def purge_expired_recordings() -> int:
    """保存期限を過ぎた録音を消す。

    ★ S3 のライフサイクル規則だけに任せない。テナントごとに
      保存期間が違うので、規則を 1 つでは書けない。
      またこちらで消せば「消した」記録が監査ログに残る。
    """
    async with system_tx() as conn:
        rows = await conn.fetch(
            """
            select id, tenant_id, storage_key
              from recordings
             where status = 'stored'
               and deleted_at is null
               and retention_until < now()
             limit $1
            """,
            BATCH_SIZE,
        )

    results = await _bounded(
        RECORDING_CONCURRENCY, [_purge_one(row) for row in rows]
    )

    purged = 0
    for row, result in zip(rows, results, strict=True):
        if isinstance(result, BaseException):
            logger.warn(
                "録音の削除に失敗しました", recording_id=str(row["id"]), error=str(result)
            )
        else:
            purged += 1

    if len(rows) == BATCH_SIZE:
        logger.warn("録音の削除が上限に達しました（積み残しの可能性）", batch=BATCH_SIZE)
    return purged


async def _purge_one(row) -> None:
    if row["storage_key"]:
        # ★ boto3 は同期。スレッドへ逃がす
        await storage.delete_async(row["storage_key"])
    async with tenant_tx(row["tenant_id"]) as conn:
        await conn.execute(
            """
            update recordings
               set status = 'deleted', deleted_at = now(), updated_at = now()
             where id = $1
            """,
            row["id"],
        )


# ---------------------------------------------------------------- AI 分析

async def run_pending_analyses() -> int:
    """pending の分析を実行する。

    ★ 通話終了の同期処理にしないための受け皿。
      session.py が pending の行を作り、ここが拾う。
    """
    async with system_tx() as conn:
        rows = await conn.fetch(
            """
            select a.id, a.tenant_id, a.call_session_id, t.full_text
              from ai_analyses a
              join transcripts t
                on t.tenant_id = a.tenant_id and t.id = a.transcript_id
             where a.status in ('pending', 'failed')
               and a.attempts < $1
               and t.status = 'done'
               and coalesce(t.full_text, '') <> ''
             order by a.created_at
             limit $2
            """,
            MAX_ATTEMPTS,
            BATCH_SIZE,
        )

    # ★ LLM の応答待ちは重ねられる。順番に待つと 1 サイクルが
    #   「件数 × 応答時間」になり、溜まる速さに追いつけなくなる
    results = await _bounded(
        ANALYSIS_CONCURRENCY, [_analyze_one(row) for row in rows]
    )

    done = 0
    for row, result in zip(rows, results, strict=True):
        if isinstance(result, AnalysisError):
            await _mark_analysis_failed(row["tenant_id"], row["id"], str(result))
            logger.warn("通話分析に失敗しました", analysis_id=str(row["id"]), error=str(result))
        elif isinstance(result, BaseException):
            await _mark_analysis_failed(row["tenant_id"], row["id"], str(result))
            logger.error(
                "通話分析で予期しない例外", analysis_id=str(row["id"]), error=str(result)
            )
        else:
            done += 1

    if len(rows) == BATCH_SIZE:
        logger.warn("通話分析が上限に達しました（積み残しの可能性）", batch=BATCH_SIZE)
    return done


async def _analyze_one(row) -> None:
    result = await analyze(row["full_text"])
    await _save_analysis(row["tenant_id"], row["id"], result)


async def _save_analysis(tenant_id, analysis_id, result) -> None:
    from ..config import LLM_MODEL

    async with tenant_tx(tenant_id) as conn:
        await conn.execute(
            """
            update ai_analyses
               set status = 'done',
                   model = $2,
                   summary = $3,
                   needs = $4,
                   objections = $5,
                   sentiment = $6,
                   opportunity_score = $7,
                   suggested_disposition = $8,
                   next_action = $9::jsonb,
                   recommended_talk = $10,
                   attempts = attempts + 1,
                   last_error = null,
                   updated_at = now()
             where id = $1
            """,
            analysis_id,
            LLM_MODEL,
            result.summary,
            result.needs,
            result.objections,
            result.sentiment,
            result.opportunity_score,
            # ★ 提案するコードがマスタに無ければ null にする。
            #   外部キー違反で分析全体を落とさない
            await _valid_disposition(conn, result.suggested_disposition),
            result.next_action.model_dump_json(),
            result.recommended_talk,
        )


async def _valid_disposition(conn, code: str | None) -> str | None:
    if not code:
        return None
    exists = await conn.fetchval(
        "select 1 from disposition_codes where code = $1", code
    )
    return code if exists else None


async def _mark_analysis_failed(tenant_id, analysis_id, error: str) -> None:
    async with tenant_tx(tenant_id) as conn:
        await conn.execute(
            """
            update ai_analyses
               set status = 'failed', attempts = attempts + 1,
                   last_error = $2, updated_at = now()
             where id = $1
            """,
            analysis_id,
            error[:500],
        )


# ---------------------------------------------------------------- 実行

async def run_once() -> dict[str, int]:
    """1 サイクル。各処理は独立して失敗してよい。"""
    result = {}
    for name, fn in (
        ("recordings_fetched", fetch_recordings),
        ("recordings_purged", purge_expired_recordings),
        ("analyses_done", run_pending_analyses),
    ):
        try:
            result[name] = await fn()
        except Exception as e:  # noqa: BLE001
            # ★ 1 つが落ちても次を動かす
            logger.error(f"{name} が失敗しました", error=str(e))
            result[name] = -1
    return result


async def main_async(interval: int | None) -> None:
    await init_pool()
    try:
        while True:
            started = datetime.now(UTC)
            result = await run_once()
            # ★ 「最後に成功した時刻」を出す。動いていないことに
            #   気付けるようにするのがこのログの目的
            logger.info(
                "定期ジョブを実行しました",
                finished_at=datetime.now(UTC).isoformat(),
                elapsed_ms=int((datetime.now(UTC) - started).total_seconds() * 1000),
                **result,
            )
            if interval is None:
                return
            await asyncio.sleep(interval)
    finally:
        await _close_client()
        await close_pool()


# ★ 設定の誤りで落ちたとき、すぐ終了せずこれだけ待つ。
#
#   このコンテナは異常終了すると即座に再起動される。待たずに落ちると
#   1.3 秒間隔で再起動し続け、ログが traceback で埋まるうえ、
#   PostgreSQL 側にも認証失敗が秒間 1 回ずつ記録され続ける
#   （実際にそうなった）。総当たり攻撃と区別も付かない。
#
#   設定を直すのは人間なので、急いで再試行しても意味がない。
#   間隔を空けて、ログを読める状態に保つほうが早く復旧できる。
CONFIG_ERROR_COOLDOWN_SECONDS = 60


def main() -> None:
    parser = argparse.ArgumentParser(description="定期ジョブ")
    parser.add_argument(
        "--loop",
        type=int,
        nargs="?",
        const=JOB_INTERVAL_SECONDS,
        default=None,
        help="秒間隔で回し続ける。省略すると 1 回だけ実行する",
    )
    args = parser.parse_args()

    try:
        asyncio.run(main_async(args.loop))
    except DatabaseCredentialsRejected as e:
        # ★ traceback を出さない。読む人に必要なのは「どこを直すか」だけ
        logger.error("起動できません（設定の誤り）", reason=str(e))
        logger.error(
            "確認する場所",
            hint="このサービスの DATABASE_URL と、DB のロードのパスワード。"
            "片方だけ変えるとこの状態になります",
        )
        time.sleep(CONFIG_ERROR_COOLDOWN_SECONDS)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
