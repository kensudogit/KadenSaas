"""構造化ログ。

★ 通話に関するログには必ず call_sid を入れる。障害の調査は
  「Twilio 側の CallSid」から始まるので、それで grep できないと
  ログがあっても追えない。
★ 電話番号と録音 URL をそのまま出さない。ログは長期保存され、
  閲覧範囲も広い。末尾 4 桁だけにする。
"""

from __future__ import annotations

import json
import logging
import sys

_handler = logging.StreamHandler(sys.stdout)
_handler.setFormatter(logging.Formatter("%(message)s"))

_logger = logging.getLogger("kaden.voice")
_logger.setLevel(logging.INFO)
_logger.addHandler(_handler)
_logger.propagate = False


def _emit(level: str, message: str, **fields) -> None:
    payload = {"level": level, "message": message}
    payload.update(fields)
    _logger.info(json.dumps(payload, ensure_ascii=False, default=str))


def info(message: str, **fields) -> None:
    _emit("info", message, **fields)


def warn(message: str, **fields) -> None:
    _emit("warn", message, **fields)


def error(message: str, **fields) -> None:
    _emit("error", message, **fields)


def mask_phone(e164: str | None) -> str:
    """★ ログに残すのは末尾 4 桁だけ。"""
    if not e164:
        return "(none)"
    return f"***{e164[-4:]}"
