"""Twilio Media Stream へ AI 音声を 20ms フレームで送る。"""

from __future__ import annotations

import asyncio
import base64
import json
from collections.abc import Awaitable, Callable

from fastapi import WebSocket

# 8kHz μ-law: 1 サンプル = 1 バイト、20ms = 160 バイト
_FRAME_BYTES = 160


SendFn = Callable[[str], Awaitable[None]]


async def play_ulaw_on_stream(send_json: SendFn, stream_sid: str, ulaw: bytes) -> None:
    """μ-law を Twilio に順次送る。"""
    if not ulaw or not stream_sid:
        return

    for offset in range(0, len(ulaw), _FRAME_BYTES):
        chunk = ulaw[offset : offset + _FRAME_BYTES]
        payload = base64.b64encode(chunk).decode("ascii")
        message = {
            "event": "media",
            "streamSid": stream_sid,
            "media": {"payload": payload},
        }
        await send_json(json.dumps(message))
        await asyncio.sleep(0.02)


def websocket_sender(ws: WebSocket) -> SendFn:
    async def _send(text: str) -> None:
        await ws.send_text(text)

    return _send
