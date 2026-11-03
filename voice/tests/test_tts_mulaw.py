"""TTS ユーティリティのテスト。"""

from __future__ import annotations

import io

try:
    import audioop
except ModuleNotFoundError:
    import audioop_lts as audioop  # type: ignore[no-redef]
import struct
import wave

from app.realtime.tts import _wav_to_ulaw_8k


def _make_wav(framerate: int, seconds: float = 0.04) -> bytes:
    nframes = int(framerate * seconds)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(framerate)
        frames = b"".join(
            struct.pack("<h", int(10000 * (i / max(nframes, 1)))) for i in range(nframes)
        )
        wf.writeframes(frames)
    return buf.getvalue()


def test_wav_to_ulaw_8k_resamples():
    ulaw = _wav_to_ulaw_8k(_make_wav(24000))
    assert len(ulaw) > 0
    pcm = audioop.ulaw2lin(ulaw, 2)
    assert len(pcm) == len(ulaw) * 2
