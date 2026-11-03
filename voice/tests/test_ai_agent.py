"""AI 架電エージェントの単体テスト。"""

from __future__ import annotations

import pytest

from app.realtime import ai_agent
from app.realtime.ai_agent import OutboundAiAgent


def test_opening_line_uses_script_first_line():
    agent = OutboundAiAgent(
        campaign_name="デモ",
        campaign_script="こんにちは、〇〇株式会社の田中です。\nご案内があります。",
    )
    assert agent.opening_line() == "こんにちは、〇〇株式会社の田中です。"
    assert agent.opening_line() == ""


def test_system_prompt_contains_script():
    agent = OutboundAiAgent(campaign_script="アポイントの依頼")
    assert "アポイントの依頼" in agent.system_prompt()


@pytest.mark.asyncio
async def test_generate_reply_without_key(monkeypatch):
    monkeypatch.setattr(ai_agent, "LLM_API_KEY", "")
    agent = OutboundAiAgent()
    with pytest.raises(RuntimeError):
        await ai_agent.generate_reply(agent, "はい")
