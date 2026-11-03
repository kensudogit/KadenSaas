"""架電中の AI 会話エージェント。

★ 通話分析（ai/analyze.py）とは別物。こちらはリアルタイムの短い応答。
  長い思考や構造化出力は不要で、レイテンシを優先する。

★ スクリプトは「台本」ではなく LLM への指示。相手の発言に合わせて
  自然に会話する。台本の一読り上げは TTS の仕事ではない。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .. import logger
from ..config import LLM_API_KEY, LLM_MAX_TOKENS, LLM_MODEL

_DEFAULT_SCRIPT = (
    "御社のサービスについてご案内したいと思い、お電話いたしました。"
    "ご担当者様はいらっしゃいますでしょうか。"
)


@dataclass
class OutboundAiAgent:
    """1 通話ぶんの会話状態。"""

    campaign_name: str = ""
    campaign_script: str = ""
    history: list[dict[str, str]] = field(default_factory=list)
    greeted: bool = False

    def system_prompt(self) -> str:
        script = (self.campaign_script or "").strip() or _DEFAULT_SCRIPT
        name = (self.campaign_name or "").strip()
        campaign_line = f"キャンペーン名: {name}\n" if name else ""
        return f"""あなたは日本語で電話架電を行う AI オペレーターです。

{campaign_line}次のトークスクリプト・目的を踏まえ、相手と自然な会話をしてください。
スクリプト:
---
{script}
---

守ること:
- 1 回の発話は短く（2〜4 文程度）。電話なので長文禁止。
- 相手が忙しい・断る場合は無理に続けない。丁寧に終話の方向へ。
- 嘘の約束や、書き起こしに無い事実の断言をしない。
- 敬語（です・ます）を使う。"""

    def opening_line(self) -> str:
        """応答直後の最初の一言。"""
        if self.greeted:
            return ""
        self.greeted = True
        script = (self.campaign_script or "").strip()
        if script:
            first = script.split("\n")[0].strip()
            if first:
                return first
        return (
            "お世話になっております。"
            + (f"{self.campaign_name}の" if self.campaign_name else "")
            + "担当でございます。少しお時間よろしいでしょうか。"
        )


def _client():
    if not LLM_API_KEY:
        raise RuntimeError("LLM_API_KEY が未設定です")
    import anthropic

    return anthropic.Anthropic(api_key=LLM_API_KEY)


async def generate_reply(agent: OutboundAiAgent, customer_text: str) -> str:
    """相手の発話に対する AI の返答文を生成する。"""
    customer_text = customer_text.strip()
    if not customer_text:
        return ""

    agent.history.append({"role": "user", "content": customer_text})
    messages = [{"role": m["role"], "content": m["content"]} for m in agent.history]

    client = _client()

    try:
        response = client.messages.create(
            model=LLM_MODEL,
            max_tokens=min(512, LLM_MAX_TOKENS),
            system=agent.system_prompt(),
            messages=messages,
        )
    except Exception as e:  # noqa: BLE001
        logger.warn("LLM の会話生成に失敗しました", error=str(e))
        return "申し訳ございません、少々お待ちください。"

    text_parts: list[str] = []
    for block in response.content:
        if getattr(block, "type", None) == "text":
            text_parts.append(getattr(block, "text", ""))

    reply = "".join(text_parts).strip()
    if not reply:
        reply = "承知いたしました。他にご質問はございますか。"

    agent.history.append({"role": "assistant", "content": reply})
    logger.info("AI 応答を生成しました", chars=len(reply))
    return reply
