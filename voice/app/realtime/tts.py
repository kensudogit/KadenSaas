"""テキストを Twilio Media Stream 向け μ-law 8kHz に変換する。

★ ベンダーは差し替え可能。既定は OpenAI Speech API（TTS_API_KEY）。
  未設定のときは null で、文字起こしには AI 発話を残すが音声は流さない。
"""

from __future__ import annotations

import abc
import io

try:
    import audioop
except ModuleNotFoundError:  # Python 3.13+
    import audioop_lts as audioop  # type: ignore[no-redef]
import wave

from .. import logger
from ..config import TTS_API_KEY, TTS_MODEL, TTS_PROVIDER, TTS_VOICE


class SpeechSynthesizer(abc.ABC):
    name: str = "unknown"

    @abc.abstractmethod
    async def synthesize_ulaw(self, text: str) -> bytes:
        """8kHz μ-law の生バイト列。"""


class NullSpeechSynthesizer(SpeechSynthesizer):
    name = "null"

    async def synthesize_ulaw(self, text: str) -> bytes:
        return b""


class OpenAiSpeechSynthesizer(SpeechSynthesizer):
    name = "openai"

    def __init__(self) -> None:
        import httpx

        self._client = httpx.AsyncClient(timeout=30.0)

    async def synthesize_ulaw(self, text: str) -> bytes:
        if not text.strip():
            return b""
        response = await self._client.post(
            "https://api.openai.com/v1/audio/speech",
            headers={
                "Authorization": f"Bearer {TTS_API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "model": TTS_MODEL,
                "voice": TTS_VOICE,
                "input": text,
                "response_format": "wav",
            },
        )
        response.raise_for_status()
        return _wav_to_ulaw_8k(response.content)


def _wav_to_ulaw_8k(wav_bytes: bytes) -> bytes:
    """任意サンプルレートの WAV を 8kHz μ-law に変換する。"""
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        framerate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())

    if channels == 2:
        frames = audioop.tomono(frames, sample_width, 0.5, 0.5)
        channels = 1

    if sample_width != 2:
        frames = audioop.lin2lin(frames, sample_width, 2)
        sample_width = 2

    if framerate != 8000:
        frames, _ = audioop.ratecv(frames, 2, 1, framerate, 8000, None)

    return audioop.lin2ulaw(frames, 2)


_instance: SpeechSynthesizer | None = None


def get_synthesizer() -> SpeechSynthesizer:
    global _instance
    if _instance is not None:
        return _instance

    if TTS_PROVIDER == "openai":
        if not TTS_API_KEY:
            logger.warn("TTS_PROVIDER=openai ですが TTS_API_KEY が空です（null TTS）")
            _instance = NullSpeechSynthesizer()
        else:
            _instance = OpenAiSpeechSynthesizer()
    else:
        logger.info("TTS は null です（AI 発話は文字起こしのみ）")
        _instance = NullSpeechSynthesizer()

    return _instance
