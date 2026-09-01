"""録音の再生。

★ ここにあるのは、S3 の資格情報を持つのが voice だけだから。
  api（Spring Boot）に署名を作らせると、保管先の鍵を 2 つのサービスに
  配ることになる。api は録音の「有無」だけを返し、実際の URL はここが出す。

★ 録音は通話相手の音声そのもので、本人の同意のもとに預かっているもの。
  「誰でも見られる」状態にしない。オペレーターは自分がかけた通話だけ、
  manager 以上は自テナントの全通話。テナントの境界は RLS が担保する。

★ 参照を必ず記録する（recording_access_logs）。録音の管理で効くのは
  「見られない」ことより「誰が見たか分かる」こと。記録の書き込みが
  失敗したら URL を返さない。記録の無い再生を成立させない。

★ URL は短命（既定 5 分）。長い URL はチャットや議事録に貼られて残り、
  権限の外に出ていく。
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request

from .. import logger, storage
from ..db.engine import tenant_tx
from ..security import AuthUser, current_user

router = APIRouter(prefix="/recordings", tags=["recordings"])

# ★ 再生を始めるには十分で、貼られて残っても短時間で無効になる長さ
URL_TTL_SECONDS = 300


@router.get("/{recording_id}/url")
async def playback_url(
    recording_id: UUID,
    request: Request,
    user: AuthUser = Depends(current_user),
):
    """再生用の期限付き URL を返す。"""

    async with tenant_tx(user.tenant_id) as conn:
        row = await conn.fetchrow(
            """
            select r.storage_key, r.status, r.content_type, r.duration_seconds,
                   r.deleted_at, cs.operator_id
              from recordings r
              join call_sessions cs on cs.id = r.call_session_id
             where r.id = $1
            """,
            recording_id,
        )

        # ★ 「無い」と「見せない」を同じ 404 で返す。区別すると、
        #   id を総当たりして他テナントに録音が存在することが分かる
        if row is None:
            raise HTTPException(status_code=404, detail={"error": "not_found"})

        if not user.is_manager and row["operator_id"] != user.user_id:
            logger.warn(
                "自分以外の通話の録音を要求されました",
                recording_id=str(recording_id),
                user_id=str(user.user_id),
            )
            raise HTTPException(status_code=404, detail={"error": "not_found"})

        if row["status"] != "stored" or row["deleted_at"] is not None:
            # ★ こちらは理由を返す。自分に見せてよい録音について
            #   「まだ取得中」なのか「保存期限で消えた」のかは、
            #   利用者が知る必要がある
            raise HTTPException(
                status_code=409,
                detail={
                    "error": "not_available",
                    "message": (
                        "録音は保存期限を過ぎて削除されています"
                        if row["deleted_at"] is not None
                        else "録音がまだ保存されていません（取得中か、取得に失敗しています）"
                    ),
                },
            )

        # ★ 記録が先。URL を出してから記録すると、記録に失敗した再生が
        #   成立してしまう。同じトランザクションなので、
        #   ここで落ちれば URL も返らない
        await conn.execute(
            """
            insert into recording_access_logs (tenant_id, recording_id, user_id, action, ip)
            values ($1, $2, $3, 'play', $4)
            """,
            user.tenant_id,
            recording_id,
            user.user_id,
            _client_ip(request),
        )

        storage_key = row["storage_key"]
        content_type = row["content_type"]
        duration = row["duration_seconds"]

    try:
        url = await storage.presigned_url_async(
            storage_key, expires_seconds=URL_TTL_SECONDS
        )
    except Exception as e:  # noqa: BLE001 — 保管先の設定不備を利用者に伝える
        logger.error("再生 URL を発行できませんでした", error=str(e))
        raise HTTPException(
            status_code=503,
            detail={
                "error": "storage_unavailable",
                "message": "録音の保管先に接続できません。設定を確認してください",
            },
        ) from e

    return {
        "url": url,
        "expiresInSeconds": URL_TTL_SECONDS,
        "contentType": content_type,
        "durationSeconds": duration,
    }


def _client_ip(request: Request) -> str | None:
    """記録に残す接続元。

    ★ X-Forwarded-For の先頭を採る。ロードバランサの背後では
      request.client が常に内部アドレスになり、記録の意味が無くなる。
      なりすませる値なので、認証の判断には使わない（記録だけ）。
    """
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip() or None
    return request.client.host if request.client else None
