"""JWT の検証。

★ 秘密鍵は api（Spring Boot）と同一でなければならない。
  ポリグロット構成でいちばん踏みやすいのがここで、片方で生成し直すと
  「api では通るのに voice で 401」という切り分けにくい壊れ方をする。
  両サービスに同じ JWT_SECRET を渡すこと。

★ トークンを発行するのは api だけ。voice は検証しかしない。
  発行側が 2 つあると、失効やローテーションのときに片方が取り残される。

★ tenant_id はトークンの中の値だけを信用する。クエリやヘッダから
  受け取ると、「他人の tenant_id を送れば他人のデータが見える」になる。
"""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

import jwt
from fastapi import Header, HTTPException

from .config import JWT_SECRET


@dataclass(frozen=True)
class AuthUser:
    user_id: UUID
    tenant_id: UUID
    email: str
    role: str

    @property
    def is_manager(self) -> bool:
        return self.role in ("manager", "admin")


def decode(token: str) -> AuthUser:
    try:
        claims = jwt.decode(token, JWT_SECRET, algorithms=["HS256"])
    except jwt.ExpiredSignatureError as e:
        raise HTTPException(status_code=401, detail="token_expired") from e
    except jwt.InvalidTokenError as e:
        raise HTTPException(status_code=401, detail="invalid_token") from e

    try:
        return AuthUser(
            user_id=UUID(claims["sub"]),
            # ★ api 側の JwtService が "tid" で入れている。名前を変えるときは両方同時に
            tenant_id=UUID(claims["tid"]),
            email=claims.get("email", ""),
            role=str(claims.get("role", "operator")).lower(),
        )
    except (KeyError, ValueError) as e:
        raise HTTPException(status_code=401, detail="malformed_token") from e


async def current_user(authorization: str = Header(default="")) -> AuthUser:
    """FastAPI の依存として使う。"""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing_token")
    return decode(authorization[7:])
