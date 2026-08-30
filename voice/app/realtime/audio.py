"""μ-law の変換と、発話区切りの判定。

★ audioop に依存しない。Python 3.13 で標準ライブラリから削除されたため、
  使っていると Python を上げた日に文字起こしだけが静かに壊れる。
  μ-law は 256 通りしかないので、変換表を自前で持つのがいちばん安い。

★ 無音判定はここに閉じ込める。「どこで発話が切れたか」は
  文字起こしの単位と、会話メトリクス（話した割合・沈黙）の両方に効くので、
  判定を 2 箇所に書くと数字が食い違う。
"""

from __future__ import annotations

import array

# ---------------------------------------------------------------- μ-law

def _build_ulaw_table() -> array.array:
    """μ-law（G.711）の 8bit → 16bit PCM 変換表。

    ITU-T G.711 の定義そのまま。256 通りしかないので起動時に一度作る。
    """
    table = array.array("h", [0] * 256)
    for i in range(256):
        u = ~i & 0xFF
        sign = u & 0x80
        exponent = (u >> 4) & 0x07
        mantissa = u & 0x0F
        sample = ((mantissa << 3) + 0x84) << exponent
        sample -= 0x84
        table[i] = -sample if sign else sample
    return table


_ULAW_TO_PCM = _build_ulaw_table()


def ulaw_to_pcm16(chunk: bytes) -> array.array:
    """μ-law のバイト列を 16bit PCM に変換する。"""
    return array.array("h", (_ULAW_TO_PCM[b] for b in chunk))


def rms(samples: array.array) -> float:
    """二乗平均平方根。無音判定に使う。"""
    if not samples:
        return 0.0
    total = 0
    for s in samples:
        total += s * s
    return (total / len(samples)) ** 0.5


# ---------------------------------------------------------------- 発話の区切り

class UtteranceSplitter:
    """無音の長さで発話を切る。

    ★ 短すぎる断片を上げない。「はい」「ええ」だけを 1 発話として
      文字起こしに投げると、費用ばかりかかって内容が取れない。
      MIN_UTTERANCE_MS 未満は前後にくっつける。

    ★ 逆に切らなすぎると、要約に渡すまでの待ち時間が伸びる。
      リアルタイムで担当者を支援したいなら、ここの閾値が体感を決める。
    """

    # Twilio Media Streams は 20ms/フレーム、8kHz μ-law
    FRAME_MS = 20
    # 無音とみなす振幅。μ-law 8kHz の電話帯域での経験値
    SILENCE_RMS = 500.0

    def __init__(self, silence_threshold_ms: int, min_utterance_ms: int) -> None:
        self._silence_threshold_ms = silence_threshold_ms
        self._min_utterance_ms = min_utterance_ms
        self._buffer = bytearray()
        self._voiced_ms = 0
        self._silence_ms = 0

    def push(self, chunk: bytes) -> bytes | None:
        """フレームを足す。発話が完成したらそのバイト列を返す。"""
        self._buffer.extend(chunk)

        samples = ulaw_to_pcm16(chunk)
        if rms(samples) >= self.SILENCE_RMS:
            self._voiced_ms += self.FRAME_MS
            self._silence_ms = 0
            return None

        self._silence_ms += self.FRAME_MS
        if self._silence_ms < self._silence_threshold_ms:
            return None

        # 無音が続いた。発話として成立する長さがあれば切り出す
        if self._voiced_ms >= self._min_utterance_ms:
            out = bytes(self._buffer)
            self._reset()
            return out

        # 短すぎる。捨てて次に備える（相槌だけの区間）
        self._reset()
        return None

    def flush(self) -> bytes | None:
        """通話終了時。残りを出す。"""
        if self._voiced_ms >= self._min_utterance_ms:
            out = bytes(self._buffer)
            self._reset()
            return out
        self._reset()
        return None

    @property
    def voiced_ms(self) -> int:
        return self._voiced_ms

    def _reset(self) -> None:
        self._buffer = bytearray()
        self._voiced_ms = 0
        self._silence_ms = 0
