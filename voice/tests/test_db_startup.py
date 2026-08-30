"""起動時の DB 接続失敗の扱い。

★ ここが守っているのは、実際に本番で起きた事故そのもの。
  DB 側のロードのパスワードを変え、Railway の DATABASE_URL を
  更新し忘れた結果、voice-jobs が 1.3 秒ごとに再起動し続けた。
  ログは asyncpg の traceback で埋まり、PostgreSQL 側にも認証失敗が
  秒間 1 回ずつ記録され、肝心の「どの変数を直せばよいか」は
  どこにも書かれていなかった。

★ 確かめるのは 3 点。
  1. 待っても直らない失敗（パスワード違い）は再試行しない
  2. 待てば直る失敗（DB 再起動中）は再試行する
  3. どちらの場合もログと例外にパスワードを出さない
"""

from __future__ import annotations

import asyncio

import asyncpg
import pytest

from app.db import engine


@pytest.fixture(autouse=True)
def _reset_pool(monkeypatch):
    """テスト間でプールを持ち越さない。"""
    monkeypatch.setattr(engine, "_pool", None)
    # 待ち時間は検証に不要。実時間を使うと試験が遅くなるだけ
    monkeypatch.setattr(asyncio, "sleep", _no_sleep)
    yield
    engine._pool = None


async def _no_sleep(_seconds):
    return None


def _fail_with(exc, calls):
    async def _create_pool(*_args, **_kwargs):
        calls.append(1)
        raise exc

    return _create_pool


@pytest.mark.asyncio
async def test_パスワード違いは再試行しない(monkeypatch):
    calls: list[int] = []
    monkeypatch.setattr(
        asyncpg, "create_pool", _fail_with(asyncpg.InvalidPasswordError("nope"), calls)
    )

    with pytest.raises(engine.DatabaseCredentialsRejected):
        await engine.init_pool()

    # ★ 1 回で諦める。設定を直すのは人間なので、
    #   急いで再試行しても DB に認証失敗を積むだけ
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_パスワード違いのメッセージに直す場所が書いてある(monkeypatch):
    monkeypatch.setattr(
        asyncpg, "create_pool", _fail_with(asyncpg.InvalidPasswordError("nope"), [])
    )

    with pytest.raises(engine.DatabaseCredentialsRejected) as got:
        await engine.init_pool()

    message = str(got.value)
    # ★ 「接続できません」だけでは、どこを見ればよいか分からない
    assert "DATABASE_URL" in message
    assert "サービス" in message


@pytest.mark.asyncio
async def test_例外にパスワードを含めない(monkeypatch):
    monkeypatch.setattr(engine, "DATABASE_URL", "postgresql://kaden_app:s3cr3t@db:5432/x")
    monkeypatch.setattr(
        asyncpg, "create_pool", _fail_with(asyncpg.InvalidPasswordError("nope"), [])
    )

    with pytest.raises(engine.DatabaseCredentialsRejected) as got:
        await engine.init_pool()

    message = str(got.value)
    # ★ ログは共有されるし長く残る。ロール名とホストだけあれば足りる
    assert "s3cr3t" not in message
    assert "kaden_app" in message
    assert "db:5432" in message


@pytest.mark.asyncio
async def test_一時的な接続失敗は待って再試行する(monkeypatch):
    calls: list[int] = []
    monkeypatch.setattr(
        asyncpg, "create_pool", _fail_with(ConnectionRefusedError("まだ起動中"), calls)
    )

    with pytest.raises(ConnectionRefusedError):
        await engine.init_pool()

    # ★ DB の再起動やネットワークの瞬断で落ちきらない
    assert len(calls) == engine._CONNECT_ATTEMPTS


@pytest.mark.asyncio
async def test_途中で復帰したら起動できる(monkeypatch):
    calls: list[int] = []
    sentinel = object()

    async def _create_pool(*_args, **_kwargs):
        calls.append(1)
        if len(calls) < 3:
            raise ConnectionRefusedError("まだ起動中")
        return _FakePool()

    monkeypatch.setattr(asyncpg, "create_pool", _create_pool)
    monkeypatch.setattr(engine, "assert_rls_enforced", _ok)

    await engine.init_pool()

    assert len(calls) == 3
    assert engine._pool is not None
    assert sentinel is not None


async def _ok(_conn):
    return None


class _FakeConn:
    async def fetchval(self, _sql):
        return 1


class _FakePool:
    def acquire(self):
        return _FakeAcquire()


class _FakeAcquire:
    async def __aenter__(self):
        return _FakeConn()

    async def __aexit__(self, *_exc):
        return False
