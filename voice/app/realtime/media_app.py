"""Twilio Media Streams を受ける WebSocket サーバー。

★ これを webhook のアプリと同じプロセスに載せない。
  Media Streams は 1 通話あたり毎秒 50 メッセージ（20ms ごとに 1 フレーム）。
  10 通話で毎秒 500、50 通話で毎秒 2500 のイベントが同じイベントループに乗る。
  webhook の応答が遅れると Twilio はタイムアウトして再送し、
  負荷が高いときにいちばん壊れてほしくない経路が最初に壊れる。
  スケールの軸も違う（webhook は同時ユーザー数、media は同時通話数）。

★ 音声を DB に書かない。1 通話 3 分で約 1.4MB の μ-law が流れてくる。
  ここでやるのは「文字にする」ことだけで、音声そのものは Twilio の
  録音機能に任せ、あとからジョブが S3 へ移す。

★ 落ちても通話は続く。この WebSocket が切れても Twilio 側の通話は
  切れないので、ここでの例外で通話を殺さないこと。
  文字起こしが取れないのは劣化だが、通話が切れるのは事故。
"""

from __future__ import annotations

import asyncio
import base64
import json
from uuid import UUID

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .. import logger
from ..config import TELEPHONY_ENABLED
from ..db.engine import close_pool, init_pool
from .session import CallAudioSession

media_app = FastAPI(title="KadenSaas media")


@media_app.on_event("startup")
async def _startup() -> None:
    await init_pool()
    logger.info("media ワーカーを起動しました", telephony_enabled=TELEPHONY_ENABLED)


@media_app.on_event("shutdown")
async def _shutdown() -> None:
    await close_pool()


@media_app.get("/healthz")
async def healthz() -> dict:
    # ★ webhook 側と同じパスにしてある。ロードバランサの設定を
    #   サービスごとに変えなくて済むように
    return {"ok": True}


@media_app.websocket("/media")
async def media(ws: WebSocket) -> None:
    """Twilio からの音声ストリーム。

    プロトコル（Twilio Media Streams）:
        connected → start → media（多数）→ stop

    ★ start の customParameters で call_session_id を受け取る。
      Twilio 側の StreamSid とこちらの通話を紐づける唯一の手段。
    """
    await ws.accept()

    session: CallAudioSession | None = None
    stream_sid = ""

    try:
        while True:
            raw = await ws.receive_text()
            message = json.loads(raw)
            event = message.get("event")

            if event == "start":
                start = message.get("start", {})
                stream_sid = start.get("streamSid", "")
                params = start.get("customParameters", {}) or {}
                call_session_id = params.get("call_session_id", "")

                if not call_session_id:
                    # ★ 紐づけられない音声は捨てる。どの通話か分からない
                    #   文字起こしを保存すると、後で誰のものか判断できない
                    logger.warn("call_session_id の無いストリーム", stream_sid=stream_sid)
                    await ws.close()
                    return

                session = await CallAudioSession.open(UUID(call_session_id))
                logger.info(
                    "音声ストリームを開始しました",
                    stream_sid=stream_sid,
                    call_session_id=call_session_id,
                )

            elif event == "media" and session is not None:
                payload = message.get("media", {})
                # ★ μ-law 8kHz。base64 で 20ms ずつ届く
                chunk = base64.b64decode(payload.get("payload", ""))
                track = payload.get("track", "inbound")
                await session.feed(track, chunk)

            elif event == "stop":
                logger.info("音声ストリームが終了しました", stream_sid=stream_sid)
                break

    except WebSocketDisconnect:
        # ★ 通話が切れれば当然ここに来る。異常ではない
        logger.info("音声ストリームが切断されました", stream_sid=stream_sid)
    except Exception as e:  # noqa: BLE001
        # ★ ここで例外を投げ返さない。通話は Twilio 側で継続しており、
        #   文字起こしが取れないだけで通話を壊してはいけない
        logger.error("音声ストリームでエラー", stream_sid=stream_sid, error=str(e))
    finally:
        if session is not None:
            # ★ 締めの処理は通話の切断とは独立して必ず走らせる。
            #   ここを飛ばすと、最後の発話が保存されない
            await asyncio.shield(session.close())
