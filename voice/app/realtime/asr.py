"""文字起こしエンジンの差し替え口。

★ ベンダーの SDK をドメイン側から直接呼ばない。ASR は数年で乗り換える
  前提の部品で、精度も価格もよく変わる。呼び出し側が
  「Deepgram の戻り値の形」を知っていると、乗り換えのたびに
  session.py と分析側の両方を書き換えることになる。

★ 既定は null（何もしない）。Twilio も ASR も未設定の状態で
  開発を始められるようにするため。何も設定していないのに
  「文字起こしが動かない」で悩まないよう、null であることをログに出す。

★ 費用の観点。1 発話ごとに API を叩くので、無音や相槌を投げないことが
  そのままコストに効く。切り出しは audio.UtteranceSplitter が担当する。
"""

from __future__ import annotations

import abc

from .. import logger
from ..config import ASR_API_KEY, ASR_LANGUAGE, ASR_PROVIDER, ASR_SAMPLE_RATE


class Transcriber(abc.ABC):
    """1 発話ぶんの μ-law 音声を文字にする。"""

    name: str = "unknown"

    @abc.abstractmethod
    async def transcribe(self, ulaw_audio: bytes) -> str:
        """空文字を返してよい（聞き取れなかった場合）。"""


class NullTranscriber(Transcriber):
    """何もしない。ASR 未設定でも通話は成立させる。"""

    name = "null"

    async def transcribe(self, ulaw_audio: bytes) -> str:
        return ""


class DeepgramTranscriber(Transcriber):
    """Deepgram の同期 API を 1 発話ずつ叩く。

    ★ ストリーミング API ではなく発話単位の同期 API を使っている。
      レイテンシは劣るが、接続の維持・再接続・順序保証を自前で
      持たずに済む。リアルタイム支援（通話中のトークサジェスト）まで
      やるならストリーミングに変える価値があるが、
      その判断は「実際に何ミリ秒必要か」を測ってからでよい。
    """

    name = "deepgram"
    _ENDPOINT = "https://api.deepgram.com/v1/listen"

    def __init__(self) -> None:
        import httpx

        self._client = httpx.AsyncClient(timeout=10.0)

    async def transcribe(self, ulaw_audio: bytes) -> str:
        response = await self._client.post(
            self._ENDPOINT,
            params={
                "model": "nova-2",
                "language": ASR_LANGUAGE,
                # Twilio Media Streams の生フォーマットをそのまま渡す。
                # WAV に包み直す処理を挟まないぶん、変換の取り違えが起きない
                "encoding": "mulaw",
                "sample_rate": str(ASR_SAMPLE_RATE),
                "punctuate": "true",
            },
            headers={
                "Authorization": f"Token {ASR_API_KEY}",
                "Content-Type": "audio/mulaw",
            },
            content=ulaw_audio,
        )
        response.raise_for_status()
        data = response.json()
        try:
            return data["results"]["channels"][0]["alternatives"][0]["transcript"].strip()
        except (KeyError, IndexError):
            # ★ 形が変わったら黙って空を返す。例外を投げると通話中に
            #   エラーが積み上がる。ログには残す
            logger.warn("Deepgram の応答を解釈できませんでした")
            return ""


_instance: Transcriber | None = None


def get_transcriber() -> Transcriber:
    global _instance
    if _instance is not None:
        return _instance

    if ASR_PROVIDER == "deepgram":
        _instance = DeepgramTranscriber()
    elif ASR_PROVIDER in ("google", "azure"):
        # ★ 未実装であることを黙らせない。設定したのに null が動いていた、
        #   を防ぐ
        raise NotImplementedError(
            f"ASR_PROVIDER={ASR_PROVIDER} は未実装です。"
            "deepgram を使うか、null にしてください"
        )
    else:
        logger.info("ASR は null です（文字起こしは行われません）")
        _instance = NullTranscriber()

    return _instance
