"""api（Spring Boot）から呼ばれる内部エンドポイント。

★ ここは「関門を通った発信を実行する」だけの入口。
  かけてよいかの判断はしない。判断は api 側の DialingGate が持ち、
  その結果として queued の call_session が存在することが、
  このエンドポイントを呼んでよい唯一の条件になる。

★ 判断を二重に持たない。両方に置くと、片方だけ直したときに
  「api は止めたのに voice は鳴らす」が起きる。dialer.py の
  条件付き UPDATE（queued のときだけ dialing にする）が
  「関門を通ったか」の代わりになっている。

★ JWT を要求する。api からの呼び出しでも認証を省かない。
  内部ネットワークだから安全、という前提はいつか崩れる。
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException

from .. import logger
from ..config import TELEPHONY_ENABLED
from ..security import AuthUser, current_user
from ..telephony.dialer import DialError, dial

router = APIRouter(prefix="/internal", tags=["internal"])


@router.post("/calls/{call_session_id}/dial")
async def dial_call(call_session_id: UUID, user: AuthUser = Depends(current_user)):
    """queued の通話を実際に発信する。

    ★ tenant_id はトークンから取る。パスやボディで受け取らない。
      受け取ると、他テナントの通話を発信させられる。
    """
    if not TELEPHONY_ENABLED:
        raise HTTPException(
            status_code=503,
            detail={
                "error": "telephony_disabled",
                "message": "電話機能が無効です（Twilio の設定が未完了）",
            },
        )

    try:
        call_sid = await dial(user.tenant_id, call_session_id)
    except DialError as e:
        # ★ 400 で返す。500 にすると api 側がリトライし、
        #   二重発信の危険が出る。「かけられなかった」は
        #   異常ではなく結果なので、再試行させない
        logger.warn(
            "発信できませんでした",
            call_session_id=str(call_session_id),
            reason=str(e),
        )
        raise HTTPException(
            status_code=400, detail={"error": "dial_failed", "message": str(e)}
        ) from e

    return {"ok": True, "callSid": call_sid}
