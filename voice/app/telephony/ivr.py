"""アウトバウンド IVR（Twilio Gather）。"""

from __future__ import annotations

import json
from xml.sax.saxutils import escape

from ..config import public_url

_DEFAULT_MENU = {
    "prompt": "資料請求は 1、担当者におつなぎは 2、不要は 9 を押してください。",
    "options": {
        "1": {"label": "資料請求", "say": "資料請求を承りました。担当よりご連絡いたします。"},
        "2": {"label": "担当者", "say": "担当者におつなぎします。少々お待ちください。"},
        "9": {"label": "不要", "say": "承知いたしました。失礼いたします。"},
    },
}


def _menu_dict(raw: str | dict | None) -> dict:
    if raw is None:
        return _DEFAULT_MENU
    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return _DEFAULT_MENU


def gather_twiml(call_session_id: str, welcome: str, menu_raw: str | dict | None) -> str:
    menu = _menu_dict(menu_raw)
    prompt = menu.get("prompt") or _DEFAULT_MENU["prompt"]
    text = f"{welcome.strip()} {prompt.strip()}".strip()
    action = public_url(f"/twilio/ivr/gather?call_session_id={call_session_id}")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Gather numDigits="1" action="{action}" method="POST" timeout="8">
    <Say language="ja-JP">{escape(text)}</Say>
  </Gather>
  <Say language="ja-JP">入力がありませんでした。失礼いたします。</Say>
  <Hangup/>
</Response>"""


def digit_twiml(digits: str, menu_raw: str | dict | None) -> str:
    menu = _menu_dict(menu_raw)
    options = menu.get("options") or {}
    opt = options.get(digits)
    if opt is None:
        return """<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Say language="ja-JP">恐れ入ります。選択を認識できませんでした。</Say>
  <Hangup/>
</Response>"""
    say = opt.get("say") or opt.get("label") or "ありがとうございました。"
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Say language="ja-JP">{escape(str(say))}</Say>
  <Hangup/>
</Response>"""
