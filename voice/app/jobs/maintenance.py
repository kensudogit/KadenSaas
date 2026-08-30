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

起動:
    python -m app.jobs.maintenance --loop 60
"""

from __future__ import annotations

import argparse
import asyncio
import base64
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
from ..db.engine import close_pool, init_pool, system_tx, tenant_tx

# 1 サイクルで扱う件数。多すぎると 1 回のサイクルが長引き、
# 少なすぎると溜まる。実測して調整する前提の初期値
BATCH_SIZE = 20
# ★ 何度も失敗する行を無限に試さない。人が見るべき状態にする
MAX_ATTEMPTS = 5


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

    moved = 0
    for row in rows:
        try:
            await _fetch_one(row)
            moved += 1
        except Exception as e:  # noqa: BLE001
            # ★ 1 件の失敗で全体を止めない
            await _mark_recording_failed(row["tenant_id"], row["id"], str(e))
            logger.warn(
                "録音の取得に失敗しました",
                recording_id=str(row["id"]),
                error=str(e),
            )
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

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.get(url, headers={"Authorization": f"Basic {auth}"})
        response.raise_for_status()
        audio = response.content

    key = storage.object_key(
        str(row["tenant_id"]), str(row["call_session_id"]), sid
    )
    stored = storage.put(key, audio)

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

    purged = 0
    for row in rows:
        try:
            if row["storage_key"]:
                storage.delete(row["storage_key"])
            async with tenant_tx(row["tenant_id"]) as conn:
                await conn.execute(
                    """
                    update recordings
                       set status = 'deleted', deleted_at = now(), updated_at = now()
                     where id = $1
                    """,
                    row["id"],
                )
            purged += 1
        except Exception as e:  # noqa: BLE001
            logger.warn(
                "録音の削除に失敗しました", recording_id=str(row["id"]), error=str(e)
            )
    return purged


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

    done = 0
    for row in rows:
        try:
            result = await analyze(row["full_text"])
            await _save_analysis(row["tenant_id"], row["id"], result)
            done += 1
        except AnalysisError as e:
            await _mark_analysis_failed(row["tenant_id"], row["id"], str(e))
            logger.warn("通話分析に失敗しました", analysis_id=str(row["id"]), error=str(e))
        except Exception as e:  # noqa: BLE001
            await _mark_analysis_failed(row["tenant_id"], row["id"], str(e))
            logger.error("通話分析で予期しない例外", analysis_id=str(row["id"]), error=str(e))
    return done


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
        await close_pool()


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
    asyncio.run(main_async(args.loop))


if __name__ == "__main__":
    main()
