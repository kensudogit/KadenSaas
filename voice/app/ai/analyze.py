"""通話の LLM 分析。

★ LLM の出力は「下書き」であって事実ではない。
  要約もスコアも次回アクションも、担当者が修正・承認できる形で保存する。
  ai_analyses.reviewed_by が入るまでは未確定として扱い、
  架電結果コード（人が入れる値）を AI が上書きすることは絶対にしない。

★ 自由文で受け取らない。構造化出力（output_format）で Pydantic に
  検証させる。自由文だと、集計のたびにパースを書くことになり、
  しかも「たまに形が違う」で静かに壊れる。

★ 通話終了の同期処理にしない。LLM の応答を待つと次の通話に移れない。
  session.py が ai_analyses に pending の行を作り、
  このモジュールをジョブが呼ぶ。

★ 失敗しても通話は成立している。attempts と last_error を積んで、
  あとから拾い直せるようにする。分析できない通話があることは
  劣化であって事故ではない。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from .. import logger
from ..config import LLM_API_KEY, LLM_MAX_TOKENS, LLM_MODEL

# ---------------------------------------------------------------- 出力の形

class NextAction(BaseModel):
    """次にやること。callback なら日時まで出させる。"""

    type: Literal["callback", "send_material", "close", "none"]
    # ★ ISO8601。タイムゾーン付きで出させる。「明日の14時」を
    #   そのまま保存すると、どこの 14 時か分からなくなる
    recommended_at: str | None = Field(
        default=None, description="ISO8601（タイムゾーン付き）。callback のときのみ"
    )
    note: str | None = None


class CallAnalysis(BaseModel):
    """1 通話の分析結果。

    ★ ここに挙げた項目しか保存しない。スキーマを広げるときは
      ai_analyses テーブルも同時に広げること。
    """

    summary: str = Field(description="通話の要約。3 文以内")
    needs: list[str] = Field(default_factory=list, description="顧客が求めているもの")
    objections: list[str] = Field(default_factory=list, description="懸念・断り文句")
    sentiment: Literal["positive", "neutral", "negative"]
    opportunity_score: int = Field(ge=0, le=100, description="商談確度。0-100")
    # ★ 提案であって決定ではない。人が結果コードを入れるときの初期値に使う
    suggested_disposition: str | None = Field(
        default=None, description="架電結果コードの提案（CONNECTED / APPOINTMENT など）"
    )
    next_action: NextAction
    recommended_talk: str | None = Field(
        default=None, description="次回に話すとよい内容"
    )


# ---------------------------------------------------------------- 呼び出し

_SYSTEM = """あなたは法人向けアウトバウンドコールの通話記録を分析する担当者です。

与えられた通話の書き起こしから、次回の架電に役立つ情報だけを抽出してください。

守ること:
- 書き起こしに書かれていないことを推測して書かない。
  情報が無い項目は空にするか none にする。
- opportunity_score は根拠のある範囲で付ける。会話が成立していない
  （留守電・受付で終了）通話に高いスコアを付けない。
- suggested_disposition はあくまで提案。担当者が最終的に決める。
- 個人の属性（年齢・性別・国籍など）を推測しない。"""


class AnalysisError(Exception):
    """分析できなかった。ジョブが attempts を積んで再試行する。"""


def _client():
    if not LLM_API_KEY:
        raise AnalysisError("LLM_API_KEY が未設定です")
    import anthropic

    return anthropic.Anthropic(api_key=LLM_API_KEY)


async def analyze(transcript_text: str) -> CallAnalysis:
    """書き起こしを分析して構造化された結果を返す。"""
    if not transcript_text.strip():
        raise AnalysisError("書き起こしが空です")

    client = _client()

    try:
        # ★ messages.parse に Pydantic モデルを渡すと、応答が
        #   そのモデルで検証済みの状態で返る。JSON のパースと
        #   検証を自前で書かなくて済む
        response = client.messages.parse(
            model=LLM_MODEL,
            max_tokens=LLM_MAX_TOKENS,
            system=_SYSTEM,
            messages=[
                {
                    "role": "user",
                    "content": f"次の通話を分析してください。\n\n---\n{transcript_text}\n---",
                }
            ],
            output_format=CallAnalysis,
            # ★ 適応的思考。要約だけなら不要に見えるが、
            #   「懸念の抽出」と「確度の判断」は根拠を追う必要があり、
            #   思考を許したほうが安定する
            thinking={"type": "adaptive"},
        )
    except Exception as e:  # noqa: BLE001
        raise AnalysisError(f"LLM の呼び出しに失敗しました: {e}") from e

    # ★ 安全側のガード。安全性の判定で応答が拒否された場合、
    #   content を読む前に stop_reason を見る
    if getattr(response, "stop_reason", None) == "refusal":
        raise AnalysisError("LLM が応答を拒否しました")

    result = response.parsed_output
    if result is None:
        raise AnalysisError("構造化された応答が得られませんでした")

    logger.info(
        "通話を分析しました",
        model=LLM_MODEL,
        score=result.opportunity_score,
        sentiment=result.sentiment,
    )
    return result
