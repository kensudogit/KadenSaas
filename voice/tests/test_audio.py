"""μ-law の変換と無音判定。

★ ここを速くするために書き方を変えてある（1 サンプルずつ Python で回すのを
  やめ、byte 単位の translate と二乗表に置き換えた）。速さのための書き換えは
  「同じ答えを返すこと」が担保されて初めて意味があるので、
  参照実装（G.711 の定義そのまま）と突き合わせる。

★ 256 通りしかないので全パターン試せる。サンプリングで済ませない。
"""

from __future__ import annotations

import array
import math

from app.realtime.audio import UtteranceSplitter, rms, ulaw_rms, ulaw_to_pcm16


def _reference_decode(byte: int) -> int:
    """ITU-T G.711 の定義そのまま。速さを考えない参照実装。"""
    u = ~byte & 0xFF
    sign = u & 0x80
    exponent = (u >> 4) & 0x07
    mantissa = u & 0x0F
    sample = ((mantissa << 3) + 0x84) << exponent
    sample -= 0x84
    return -sample if sign else sample


def test_変換は256通りすべてで参照実装と一致する():
    all_bytes = bytes(range(256))
    expected = [_reference_decode(b) for b in all_bytes]
    assert list(ulaw_to_pcm16(all_bytes)) == expected


def test_変換の並びが崩れていない():
    # ★ 上位/下位バイトを別々に translate して差し込んでいるので、
    #   取り違えると値が入れ替わる。バイト順の取り違えは
    #   「音が雑音になるが例外は出ない」形で出るため、明示的に見る
    chunk = bytes([0x00, 0xFF, 0x7F, 0x80, 0x01])
    assert list(ulaw_to_pcm16(chunk)) == [_reference_decode(b) for b in chunk]


def test_ulaw_rms_は変換してからのRMSと一致する():
    for chunk in (
        bytes(range(256)),
        bytes([0xFF] * 160),  # 無音に近い
        bytes([0x00] * 160),  # 最大振幅
        bytes(range(160)),
    ):
        expected = rms(ulaw_to_pcm16(chunk))
        assert math.isclose(ulaw_rms(chunk), expected, rel_tol=1e-12), chunk[:4]


def test_空の入力でも落ちない():
    assert ulaw_rms(b"") == 0.0
    assert rms(array.array("h")) == 0.0
    assert len(ulaw_to_pcm16(b"")) == 0


def _silence_frame() -> bytes:
    """RMS がしきい値を下回るフレーム。μ-law の 0xFF が振幅ゼロ付近。"""
    return bytes([0xFF] * 160)


def _voiced_frame() -> bytes:
    """RMS がしきい値を超えるフレーム。"""
    return bytes([0x00] * 160)


def test_発話は無音で切れる():
    # 無音 60ms で切る / 40ms 以上を発話とみなす
    splitter = UtteranceSplitter(silence_threshold_ms=60, min_utterance_ms=40)

    assert ulaw_rms(_voiced_frame()) >= UtteranceSplitter.SILENCE_RMS
    assert ulaw_rms(_silence_frame()) < UtteranceSplitter.SILENCE_RMS

    for _ in range(5):  # 100ms 発話
        assert splitter.push(_voiced_frame()) is None
    assert splitter.push(_silence_frame()) is None  # 20ms
    assert splitter.push(_silence_frame()) is None  # 40ms
    out = splitter.push(_silence_frame())            # 60ms → 切れる
    assert out is not None
    assert len(out) == 8 * 160  # 発話 5 + 無音 3 フレーム


def test_短すぎる相槌は捨てる():
    splitter = UtteranceSplitter(silence_threshold_ms=40, min_utterance_ms=200)
    splitter.push(_voiced_frame())          # 20ms しか無い
    splitter.push(_silence_frame())
    assert splitter.push(_silence_frame()) is None
    assert splitter.voiced_ms == 0          # 捨てて初期化されている
