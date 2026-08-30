"""voice サービスの ASGI アプリ。

★ Media Streams の WebSocket はここに載せない（app/realtime/media_app.py が持つ）。
  Media Streams は 1 通話あたり毎秒 50 メッセージ。同じイベントループに載せると、
  同時通話が増えるほど webhook の応答が遅くなり、Twilio 側がタイムアウトして
  同じイベントを再送し始める。負荷が高いときにいちばん壊れてほしくない経路が
  最初に壊れる。

起動:
    uvicorn app.app:app --port 8001                       # webhook と内部 API
    uvicorn app.realtime.media_app:media_app --port 8002  # 音声
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import logger
from .api import internal
from .config import CORS_ORIGIN, TELEPHONY_ENABLED
from .db.engine import close_pool, init_pool, pool
from .telephony import routes as telephony_routes


@asynccontextmanager
async def lifespan(_: FastAPI):
    await init_pool()
    logger.info(
        "voice サービスを起動しました",
        telephony_enabled=TELEPHONY_ENABLED,
    )
    if not TELEPHONY_ENABLED:
        # ★ 黙って電話機能なしで動くと「なぜ鳴らないのか」で時間を溶かす。
        #   起動ログで明示する
        logger.warn(
            "電話機能は無効です（TWILIO_ACCOUNT_SID / AUTH_TOKEN / CALLER_ID が未設定）。"
            "発信要求は 503 を返します"
        )
    yield
    await close_pool()


def create_app() -> FastAPI:
    app = FastAPI(title="KadenSaas voice", lifespan=lifespan)

    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in CORS_ORIGIN.split(",")],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/healthz")
    async def healthz():
        # ★ 「プロセスが生きている」ではなく「DB に繋がる」で判定する。
        #   前者だと DB 断でもロードバランサに入れられ続け、
        #   全部の webhook が 500 になってから気付くことになる
        try:
            async with pool().acquire() as conn:
                await conn.fetchval("select 1")
        except Exception:  # noqa: BLE001
            return JSONResponse(
                {"ok": False, "error": "database_unavailable"}, status_code=503
            )
        return {"ok": True, "telephony": "enabled" if TELEPHONY_ENABLED else "disabled"}

    @app.get("/")
    async def root():
        # ★ ルートが FastAPI 既定の 404 JSON になると、疎通確認のたびに
        #   「落ちているのか、パスが違うのか」を判断できない
        return {
            "service": "voice",
            "ok": True,
            "telephony": "enabled" if TELEPHONY_ENABLED else "disabled",
            "healthz": "/healthz",
        }

    # Twilio からのコールバック（署名検証あり）
    app.include_router(telephony_routes.router)
    # api（Spring Boot）からの内部呼び出し（JWT 検証あり）
    app.include_router(internal.router)

    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception):
        logger.error("未処理の例外", path=str(request.url.path), error=str(exc))
        # ★ 例外の中身を返さない。SQL や内部の識別子が漏れる
        return JSONResponse({"error": "internal_error"}, status_code=500)

    return app


app = create_app()
