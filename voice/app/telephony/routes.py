"""Twilio からのコールバック。

★ 3 つの経路が同じ通話の状態を書きに来る。
    1. 発信 API の同期レスポンス（dialer.py）
    2. statusCallback（このファイル）
    3. Media Stream の開始・終了（realtime/）
  素直に代入すると、遅れて届いた ringing が completed を上書きして
  状態が巻き戻る。更新は必ず dial_state_rank の比較付きで行う。

★ Twilio は同じ webhook を再送する。ネットワークが不安定なとき、
  タイムアウトしたとき、5xx を返したとき。二重に処理すると
  通話が 2 行に増え、KPI が水増しされる。
  webhook_events の unique 制約で transport 層の冪等を担保する。

★ 署名検証は全経路で行う。1 つでも素通しがあると、そこから
  「完了した」「相手が応答した」を偽装できる。
"""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Request, Response
from fastapi.responses import PlainTextResponse

from .. import logger
from ..config import public_wss
from ..db.engine import system_tx, tenant_tx
from .signature import SignatureError, verify

router = APIRouter(prefix="/twilio", tags=["twilio"])


# Twilio の CallStatus → こちらの dial_state
_STATUS_MAP = {
    "queued": "queued",
    "initiated": "dialing",
    "ringing": "ringing",
    "in-progress": "answered",
    "completed": "completed",
    "busy": "busy",
    "no-answer": "no_answer",
    "failed": "failed",
    "canceled": "canceled",
}


async def _verified_form(request: Request, path: str) -> dict[str, str]:
    """署名を検証してフォームを返す。失敗なら SignatureError。"""
    form = await request.form()
    data = {k: str(v) for k, v in form.items()}
    verify(path, data, request.headers.get("X-Twilio-Signature"))
    return data


async def _remember_event(provider_event_id: str, payload: dict[str, Any]) -> bool:
    """初めて見るイベントなら True。再送なら False。

    ★ tenant_id を持たないテーブルなので system_tx を使う。
      署名検証は済んでいるが、どのテナントの通話かはまだ引いていない。
    """
    async with system_tx() as conn:
        row = await conn.fetchrow(
            """
            insert into webhook_events (provider, provider_event_id, payload)
            values ('twilio', $1, $2::jsonb)
            on conflict (provider, provider_event_id) do nothing
            returning id
            """,
            provider_event_id,
            json.dumps(payload, ensure_ascii=False),
        )
        return row is not None


async def _find_call(call_sid: str) -> tuple[str, str] | None:
    """CallSid から (tenant_id, call_session_id) を引く。

    ★ RLS が有効なテーブルなので、テナントを設定せずには引けない。
      ここだけは「どのテナントか分からない状態で引きたい」ので、
      tenant_id を持たない索引テーブルではなく、
      migrator ではなくアプリロールのまま安全に引ける専用関数を使う。
    """
    async with system_tx() as conn:
        return await conn.fetchrow(
            "select tenant_id, id from call_sessions_lookup($1)", call_sid
        )


@router.post("/voice")
async def voice(request: Request) -> Response:
    """相手が応答したときに Twilio が取りに来る TwiML。

    ★ ここでの応答が遅いと、相手には無音が流れる。DB の書き込みを
      待たせない。状態の更新は statusCallback 側に任せる。
    """
    try:
        # ★ 戻り値は使わないが、検証そのものが目的。ここを飛ばすと
        #   誰でも任意の TwiML を引き出せる
        await _verified_form(request, "/twilio/voice")
    except SignatureError as e:
        logger.warn("署名検証に失敗（voice）", error=str(e))
        return PlainTextResponse("forbidden", status_code=403)

    call_session_id = request.query_params.get("call_session_id", "")

    # ★ 双方向の音声を Media Stream に流す。track="both_tracks" にしないと
    #   相手の声しか取れず、担当者の話した割合が計算できない
    twiml = f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Start>
    <Stream url="{public_wss('/media')}" track="both_tracks">
      <Parameter name="call_session_id" value="{call_session_id}" />
    </Stream>
  </Start>
  <Pause length="3600"/>
</Response>"""
    return Response(content=twiml, media_type="application/xml")


@router.post("/status")
async def status_callback(request: Request) -> Response:
    """通話の状態変化。

    ★ 順不同で届く前提で書く。dial_state_rank の比較で弾いた分も
      call_events に applied=false で残す。残さないと
      「なぜ反映されなかったか」を後から追えない。
    """
    try:
        data = await _verified_form(request, "/twilio/status")
    except SignatureError as e:
        logger.warn("署名検証に失敗（status）", error=str(e))
        return PlainTextResponse("forbidden", status_code=403)

    call_sid = data.get("CallSid", "")
    raw_status = data.get("CallStatus", "")
    dial_state = _STATUS_MAP.get(raw_status)

    if not call_sid or dial_state is None:
        logger.warn("解釈できない status", call_sid=call_sid, status=raw_status)
        return PlainTextResponse("ok")

    # ★ CallSid + 状態 で一意。同じ状態の再送は 1 回しか処理しない
    if not await _remember_event(f"{call_sid}:{raw_status}", data):
        logger.info("再送のため無視しました", call_sid=call_sid, status=raw_status)
        return PlainTextResponse("ok")

    found = await _find_call(call_sid)
    if found is None:
        # ★ 発信 API の応答より先に webhook が届くことがある。
        #   捨てずに残しておき、後続の照合で拾えるようにする
        logger.warn("未知の CallSid", call_sid=call_sid, status=raw_status)
        return PlainTextResponse("ok")

    tenant_id, call_session_id = found["tenant_id"], found["id"]

    async with tenant_tx(tenant_id) as conn:
        # ★ 単調前進。rank が今より小さい状態は反映しない
        updated = await conn.fetchrow(
            """
            update call_sessions
               set dial_state = $2,
                   answered_at = case when $2 = 'answered' and answered_at is null
                                      then now() else answered_at end,
                   ended_at = case when call_dial_state_rank($2) >= 90 and ended_at is null
                                   then now() else ended_at end,
                   duration_seconds = coalesce($3, duration_seconds),
                   updated_at = now()
             where id = $1
               and dial_state_rank < call_dial_state_rank($2)
            returning id
            """,
            call_session_id,
            dial_state,
            _int_or_none(data.get("CallDuration")),
        )

        await conn.execute(
            """
            insert into call_events
              (tenant_id, call_session_id, source, dial_state, applied, payload)
            values ($1, $2, 'status_callback', $3, $4, $5::jsonb)
            """,
            tenant_id,
            call_session_id,
            dial_state,
            updated is not None,
            json.dumps(data, ensure_ascii=False),
        )

    if updated is None:
        logger.info(
            "状態が巻き戻るため反映しませんでした",
            call_sid=call_sid,
            status=raw_status,
        )

    return PlainTextResponse("ok")


@router.post("/recording")
async def recording_callback(request: Request) -> Response:
    """録音が準備できた通知。

    ★ ここでは「録音がある」ことだけを記録し、実体の取得は
      ジョブに任せる。webhook のレスポンス内でダウンロードすると、
      数 MB の転送を Twilio のタイムアウト内で終える必要が出る。
    """
    try:
        data = await _verified_form(request, "/twilio/recording")
    except SignatureError as e:
        logger.warn("署名検証に失敗（recording）", error=str(e))
        return PlainTextResponse("forbidden", status_code=403)

    call_sid = data.get("CallSid", "")
    recording_sid = data.get("RecordingSid", "")

    if not await _remember_event(f"{recording_sid}:recording", data):
        return PlainTextResponse("ok")

    found = await _find_call(call_sid)
    if found is None:
        logger.warn("未知の CallSid（recording）", call_sid=call_sid)
        return PlainTextResponse("ok")

    tenant_id, call_session_id = found["tenant_id"], found["id"]

    async with tenant_tx(tenant_id) as conn:
        # ★ 保存期限をテナント設定から計算して入れる。
        #   「消す仕組み」を後から足すと、消し忘れた分が残り続ける
        await conn.execute(
            """
            insert into recordings
              (tenant_id, call_session_id, provider_recording_sid,
               duration_seconds, status, retention_until)
            select $1, $2, $3, $4, 'pending',
                   now() + make_interval(days => t.recording_retention_days)
              from tenants t where t.id = $1
            on conflict (provider_recording_sid) do nothing
            """,
            tenant_id,
            call_session_id,
            recording_sid,
            _int_or_none(data.get("RecordingDuration")),
        )

    logger.info("録音を受け付けました", call_sid=call_sid, recording_sid=recording_sid)
    return PlainTextResponse("ok")


def _int_or_none(raw: str | None) -> int | None:
    if raw is None or raw == "":
        return None
    try:
        return int(raw)
    except ValueError:
        return None
