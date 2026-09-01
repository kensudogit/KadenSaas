"""文字起こしが遅くても音声の受信を止めないこと。

★ ここが止まると、Twilio から毎秒 50 フレーム届く音声が受信バッファに溜まり、
  発話の時刻がずれ、無音の判定が遅れる。「ASR が遅いほど文字起こしが
  不正確になる」という、いちばん助けが要る場面で効かなくなる壊れ方をする。

★ 速さの主張はテストで固定する。feed() を await する形に戻しても
  機能は動いてしまうので、コードを読むだけでは退行に気付けない。
"""

from __future__ import annotations

import asyncio
import time
from uuid import uuid4

import pytest

from app.realtime import session as session_module
from app.realtime.audio import UtteranceSplitter
from app.realtime.session import CallAudioSession

FRAME_MS = UtteranceSplitter.FRAME_MS
VOICED = bytes([0x00] * 160)   # 20ms、しきい値を超える振幅
SILENCE = bytes([0xFF] * 160)  # 20ms、無音


class _SlowTranscriber:
    """1 発話に 200ms かかる ASR。実際の Deepgram の往復に相当する。"""

    name = "slow-test"

    def __init__(self, delay: float = 0.2) -> None:
        self.delay = delay
        self.calls = 0

    async def transcribe(self, ulaw_audio: bytes) -> str:
        self.calls += 1
        await asyncio.sleep(self.delay)
        return f"発話{self.calls}"


@pytest.fixture
def slow_asr(monkeypatch):
    t = _SlowTranscriber()
    monkeypatch.setattr(session_module, "get_transcriber", lambda: t)
    return t


def _session() -> CallAudioSession:
    """DB にも Redis にも触らずにセッションを組み立てる。

    ★ open() は所属テナントを引くために DB を使う。ここで見たいのは
      feed() の詰まり方だけなので、直接組み立てる。
    """
    s = CallAudioSession(call_session_id=uuid4(), tenant_id=uuid4())
    s._redis = None
    return s


async def _speak(s: CallAudioSession, track: str = "inbound") -> None:
    """1 発話ぶん流し込む。無音で締めて発話を確定させる。

    ★ 既定は MIN_UTTERANCE_MS=800 / SILENCE_THRESHOLD_MS=700。
      どちらも超える長さにしないと、発話として切り出されずに捨てられる。
    """
    for _ in range(50):        # 1000ms 発話（> MIN_UTTERANCE_MS）
        await s.feed(track, VOICED)
    for _ in range(40):        # 800ms 無音（> SILENCE_THRESHOLD_MS）
        await s.feed(track, SILENCE)


async def test_遅いASRでもfeedは待たされない(slow_asr):
    s = _session()

    started = time.perf_counter()
    await _speak(s)
    elapsed = time.perf_counter() - started

    # ★ 発話は確定してキューに入っているが、feed() は ASR の 200ms を待っていない
    assert elapsed < 0.05, f"feed() が {elapsed * 1000:.0f}ms 待たされている"

    # 締めると待ち行列が処理され、結果が入る
    s._segments.clear()
    await s._drain()
    assert slow_asr.calls >= 1
    assert s._segments, "文字起こしの結果が入っていない"


async def test_トラックごとに並行して文字起こしする(slow_asr):
    s = _session()

    await _speak(s, "inbound")
    await _speak(s, "outbound")

    started = time.perf_counter()
    await s._drain()
    elapsed = time.perf_counter() - started

    assert slow_asr.calls == 2
    # ★ 直列なら 400ms。並行なら 200ms 強で終わる
    assert elapsed < 0.35, f"トラックが直列に処理されている（{elapsed * 1000:.0f}ms）"


async def test_ASRが詰まっても発話を捨てて通話は続く(monkeypatch):
    """★ 捨てるのは劣化。止まるのは事故。優先順位を固定する。"""
    stuck = asyncio.Event()

    class _Stuck:
        name = "stuck-test"

        async def transcribe(self, ulaw_audio: bytes) -> str:
            await stuck.wait()
            return ""

    monkeypatch.setattr(session_module, "get_transcriber", lambda: _Stuck())
    s = _session()

    started = time.perf_counter()
    # 待ち行列の深さ（8）を超える数の発話を流す
    for _ in range(session_module._QUEUE_DEPTH + 4):
        await _speak(s)
    elapsed = time.perf_counter() - started

    assert elapsed < 0.5, f"ASR が固まると feed() も止まっている（{elapsed * 1000:.0f}ms）"
    assert s._dropped > 0, "捨てた記録が残っていない"

    stuck.set()
    await s._drain()


async def test_締めは待ち切れなくても返る(monkeypatch):
    """★ 上限が無いと、固まった 1 通話が接続とタスクを握ったまま返らない。"""
    class _Stuck:
        name = "stuck-test"

        async def transcribe(self, ulaw_audio: bytes) -> str:
            await asyncio.sleep(3600)
            return ""

    monkeypatch.setattr(session_module, "get_transcriber", lambda: _Stuck())
    monkeypatch.setattr(session_module, "_DRAIN_TIMEOUT_SECONDS", 0.2)

    s = _session()
    await _speak(s)

    started = time.perf_counter()
    await s._drain()
    elapsed = time.perf_counter() - started

    assert elapsed < 1.0, f"締めが返らない（{elapsed:.1f}s）"
