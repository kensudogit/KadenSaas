"""実際に Twilio へ発信する唯一の場所。

★ このプロジェクトで Twilio の Calls API を呼ぶのはこの関数だけ。
  守れているかは grep 1 回で確認できる:

      grep -rn "client.calls.create" voice/app | grep -v dialer.py

  1 件でも出たら、関門を通らない発信経路ができている。

★ 関門（api 側の DialingGate）を通っていない発信を実行しない。
  ここでは「queued の call_session が既に存在すること」を条件にしている。
  行が無ければ鳴らせないので、関門を迂回する経路が構造的に作れない。
  この条件を緩めた瞬間、断った相手に電話がかかる余地が生まれる。

★ 二重発信は DB の部分ユニークインデックスで止まっている前提だが、
  ここでも queued → dialing の遷移を条件付き UPDATE にしてある。
  同じ行に対する 2 回の発信要求は、2 回目が 0 行更新になって弾かれる。
"""

from __future__ import annotations

from uuid import UUID

from twilio.base.exceptions import TwilioRestException
from twilio.rest import Client

from .. import logger
from ..config import (
    TELEPHONY_ENABLED,
    TWILIO_ACCOUNT_SID,
    TWILIO_AUTH_TOKEN,
    TWILIO_MACHINE_DETECTION,
    public_url,
)
from ..db.engine import tenant_tx

_client: Client | None = None


class DialError(Exception):
    """発信できなかった。理由は利用者に見せてよい文言にする。"""


def _client_or_fail() -> Client:
    global _client
    if not TELEPHONY_ENABLED:
        raise DialError(
            "電話機能が無効です（TWILIO_ACCOUNT_SID / AUTH_TOKEN / CALLER_ID が未設定）"
        )
    if _client is None:
        _client = Client(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN)
    return _client


async def dial(tenant_id: UUID | str, call_session_id: UUID | str) -> str:
    """queued の通話を実際に発信する。返り値は CallSid。

    ★ 状態遷移を先に済ませてから Twilio を呼ぶ。逆にすると、
      Twilio が成功したのに DB 更新に失敗した場合に「鳴っているのに
      記録が無い通話」ができる。先に dialing にしておけば、
      Twilio 側が失敗したときに failed へ倒せばよい（記録は残る）。
    """
    async with tenant_tx(tenant_id) as conn:
        # ★ queued 以外は発信しない。blocked（関門が止めた）や
        #   completed（終わっている）を鳴らさないための条件
        row = await conn.fetchrow(
            """
            update call_sessions
               set dial_state = 'dialing'
             where id = $1 and dial_state = 'queued'
            returning id, from_e164, to_e164, campaign_id
            """,
            call_session_id,
        )
        if row is None:
            # ★ 行が無い＝関門を通っていない、あるいは既に発信済み。
            #   どちらも「鳴らしてはいけない」なので同じ扱いにする
            raise DialError(
                "発信できる状態の通話がありません"
                "（関門を通っていないか、すでに発信済みです）"
            )

        # ★ テナント設定を読む。環境変数の既定値に落ちるのは
        #   行が無いときだけ。留守番電話の検出方法は業種で変えたいことがあり、
        #   全テナント共通にすると片方の要求で全部が動く
        telephony = await conn.fetchrow(
            """
            select recording_enabled, machine_detection
              from tenant_telephony where tenant_id = $1
            """,
            tenant_id,
        )
        recording_enabled = telephony["recording_enabled"] if telephony else False
        machine_detection = (
            telephony["machine_detection"] if telephony else TWILIO_MACHINE_DETECTION
        )

    try:
        client = _client_or_fail()
        call = client.calls.create(
            to=row["to_e164"],
            from_=row["from_e164"],
            # ★ 応答時の TwiML。ここで Media Stream を張る
            url=public_url(f"/twilio/voice?call_session_id={call_session_id}"),
            method="POST",
            # ★ 全ての状態変化を受け取る。completed だけにすると、
            #   鳴らずに終わった通話の理由が分からない
            status_callback=public_url("/twilio/status"),
            status_callback_method="POST",
            status_callback_event=["initiated", "ringing", "answered", "completed"],
            # ★ 留守番電話の検出。人が出たのか機械が出たのかで
            #   KPI の「接続」の意味が変わる
            machine_detection=machine_detection,
            record=bool(recording_enabled),
            recording_status_callback=public_url("/twilio/recording"),
            recording_status_callback_method="POST",
        )
    except (TwilioRestException, DialError) as e:
        # ★ 失敗も記録する。dial_state = failed は終端なので、
        #   二重発信の部分ユニークインデックスから外れ、再架電できる
        async with tenant_tx(tenant_id) as conn:
            await conn.execute(
                """
                update call_sessions
                   set dial_state = 'failed', ended_at = now()
                 where id = $1 and dial_state_rank < 90
                """,
                call_session_id,
            )
            await conn.execute(
                """
                insert into call_events (tenant_id, call_session_id, source, dial_state, applied, payload)
                values ($1, $2, 'api', 'failed', true, $3::jsonb)
                """,
                tenant_id,
                call_session_id,
                _json_error(str(e)),
            )
        logger.error(
            "発信に失敗しました",
            call_session_id=str(call_session_id),
            to=logger.mask_phone(row["to_e164"]),
            error=str(e),
        )
        raise DialError(f"発信に失敗しました: {e}") from e

    # ★ CallSid を記録する。通話の同一性はこれで担保するので、
    #   ここが入らないと以降の webhook が紐付かない
    async with tenant_tx(tenant_id) as conn:
        await conn.execute(
            "update call_sessions set provider_call_sid = $2 where id = $1",
            call_session_id,
            call.sid,
        )

    logger.info(
        "発信しました",
        call_sid=call.sid,
        call_session_id=str(call_session_id),
        to=logger.mask_phone(row["to_e164"]),
    )
    return call.sid


def _json_error(message: str) -> str:
    import json

    return json.dumps({"error": message}, ensure_ascii=False)
