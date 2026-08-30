"""Twilio Webhook の署名検証。

★ 検証しないと、誰でも「通話が完了した」「相手が応答した」を偽装できる。
  架電 SaaS では、それは KPI の改ざんであり、録音の差し替えであり、
  DNC 判定の迂回でもある。必ず通す。

★ 署名の対象は「Twilio が呼び出した URL の文字列そのもの」。
  request.url から組み立ててはいけない。リバースプロキシ（ALB / Cloud Run /
  Railway）の後ろでは scheme が http になり、Host が内部名になる。
  Twilio が署名に使った公開 URL と 1 文字でも違えば一致しない。
  だから config.public_url() だけを使う。

★ 「Webhook が全件 403」のとき、原因は 9 割が次のどれか。
    1. PUBLIC_BASE_URL と Twilio Console に登録した URL が違う
    2. PUBLIC_BASE_URL の末尾にスラッシュがある
    3. Auth Token ではなく API Key Secret を使っている
    4. クエリ文字列付きの URL を登録したが、こちらで落としている
  デバッグを速くするため、失敗時にどの URL で検証したかをログに出す
  （署名そのものは出さない）。
"""

from __future__ import annotations

from twilio.request_validator import RequestValidator

from .. import logger
from ..config import TWILIO_AUTH_TOKEN, public_url


class SignatureError(Exception):
    """署名が一致しない。呼び出し側は 403 を返す。"""


_validator: RequestValidator | None = None


def _get_validator() -> RequestValidator:
    global _validator
    if _validator is None:
        if not TWILIO_AUTH_TOKEN:
            raise SignatureError("TWILIO_AUTH_TOKEN が未設定です")
        # ★ Auth Token で検証する。API Key Secret ではない
        _validator = RequestValidator(TWILIO_AUTH_TOKEN)
    return _validator


def verify(path: str, form: dict[str, str], signature: str | None) -> None:
    """署名を検証する。一致しなければ SignatureError。

    :param path: ルーティングのパス（例 "/twilio/status"）。
                 公開 URL の組み立ては public_url() に任せる。
    :param form: application/x-www-form-urlencoded のボディを dict にしたもの
    :param signature: X-Twilio-Signature ヘッダ
    """
    if not signature:
        raise SignatureError("X-Twilio-Signature ヘッダがありません")

    url = public_url(path)
    if not _get_validator().validate(url, form, signature):
        # ★ 検証に使った URL を出す。ここが分からないと、
        #   「URL がずれている」のか「トークンが違う」のか切り分けられない。
        #   署名とトークンは出さない
        logger.warn(
            "Twilio の署名が一致しませんでした",
            verified_url=url,
            hint="PUBLIC_BASE_URL と Twilio Console の URL が一致しているか確認してください",
        )
        raise SignatureError(f"署名が一致しません（検証に使った URL: {url}）")
