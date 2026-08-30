"""Twilio 署名検証のテスト。

★ 「署名なしで 403」だけでは足りない。検証が壊れていて常に 403 を返す
  実装でも、そのテストは通ってしまう。正しい署名が通ることを
  必ず一緒に確かめる（陽性対照）。

★ 「Webhook が全件 403」は架電 SaaS でいちばん時間を溶かす症状で、
  原因はほぼ URL のずれ。ここで URL の組み立て方まで固定しておく。
"""

from __future__ import annotations

import os

import pytest

# 設定モジュールは import 時に検証して落ちる。環境は conftest.py が用意する
# （各ファイルで設定していると、書き忘れたファイルを足した瞬間に収集ごと失敗する）

from twilio.request_validator import RequestValidator  # noqa: E402

from app.config import public_url  # noqa: E402
from app.telephony.signature import SignatureError, verify  # noqa: E402

AUTH_TOKEN = os.environ["TWILIO_AUTH_TOKEN"]
FORM = {"CallSid": "CA_test", "CallStatus": "completed", "CallDuration": "42"}


def _sign(path: str, form: dict[str, str]) -> str:
    """Twilio と同じ方法で署名を作る。"""
    return RequestValidator(AUTH_TOKEN).compute_signature(public_url(path), form)


def test_正しい署名は通る():
    """★ 陽性対照。これが無いと「常に 403」の実装でもテストが通る。"""
    verify("/twilio/status", FORM, _sign("/twilio/status", FORM))


def test_署名が無ければ拒否する():
    with pytest.raises(SignatureError):
        verify("/twilio/status", FORM, None)


def test_ボディを改竄したら拒否する():
    """★ 通話時間を書き換えて KPI を水増しする、が防げること。"""
    signature = _sign("/twilio/status", FORM)
    tampered = dict(FORM, CallDuration="9999")
    with pytest.raises(SignatureError):
        verify("/twilio/status", tampered, signature)


def test_別のパスの署名は通らない():
    """★ recording 用の署名で status を叩けないこと。"""
    signature = _sign("/twilio/recording", FORM)
    with pytest.raises(SignatureError):
        verify("/twilio/status", FORM, signature)


def test_URL_が_1_文字違うと通らない():
    """★ これが「Webhook が全件 403」の正体。

    PUBLIC_BASE_URL と Twilio Console の URL がずれると、
    署名は正しいのに一致しない。末尾のスラッシュだけでも起きる。
    その挙動をテストとして固定しておく。
    """
    wrong_url = public_url("/twilio/status") + "/"
    signature = RequestValidator(AUTH_TOKEN).compute_signature(wrong_url, FORM)
    with pytest.raises(SignatureError):
        verify("/twilio/status", FORM, signature)
