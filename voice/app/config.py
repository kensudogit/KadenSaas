"""環境変数の一元管理と起動時検証。

★ 検証は「最初の 1 件で投げる」のではなく、全部集めてから一度に投げる。
  コンテナ／PaaS では 1 件直すたびに再デプロイなので、6 個足りなければ
  6 回デプロイし直すことになる。まとめて出す。

★ 架電特有で最も落としやすいのは PUBLIC_BASE_URL の形。Twilio に登録した
  URL と 1 文字でも違うと署名が一致せず、Webhook が全件 403 になる。
  しかも「電話は鳴るのに結果が記録されない」という分かりにくい壊れ方をする。
  ここは値の存在だけでなく形まで見る。

★ Twilio の 3 点セットは「全部空なら電話機能を無効にして起動」を許す。
  1 つだけ空だと起動しない。中途半端な設定で立ち上がるほうが危険で、
  署名検証だけが無効な状態などを作らないため。
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from datetime import time
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from dotenv import load_dotenv

load_dotenv()

_problems: list[str] = []


def _required(name: str, hint: str) -> str:
    value = (os.environ.get(name) or "").strip()
    if not value:
        _problems.append(f"{name} が設定されていません — {hint}")
        return ""
    return value


def _optional(name: str, fallback: str = "") -> str:
    return (os.environ.get(name) or "").strip() or fallback


def _int(name: str, fallback: int) -> int:
    raw = _optional(name, str(fallback))
    try:
        return int(raw)
    except ValueError:
        _problems.append(f"{name} は整数である必要があります。実際の値: {raw!r}")
        return fallback


def _bool(name: str, fallback: bool) -> bool:
    return _optional(name, "true" if fallback else "false").lower() == "true"


def _time(name: str, fallback: str) -> time:
    raw = _optional(name, fallback)
    try:
        hh, mm = raw.split(":")
        return time(int(hh), int(mm))
    except (ValueError, TypeError):
        _problems.append(f"{name} は HH:MM 形式。実際の値: {raw!r}")
        return time(9, 0)


def _platform_https_origin() -> str:
    """PaaS が付与する公開ホスト名から origin を組み立てる。

    PUBLIC_BASE_URL を先に要求すると、初回デプロイでは URL がまだ無い、
    という鶏卵になる。ドメインが取れるなら https で補う。
    ただし Twilio に登録する URL と一致させる必要は、補完後も変わらない。
    """
    for key in ("PUBLIC_HOST", "RAILWAY_PUBLIC_DOMAIN", "FLY_APP_NAME"):
        host = _optional(key).rstrip("/")
        if not host:
            continue
        if key == "FLY_APP_NAME":
            host = f"{host}.fly.dev"
        return host if host.startswith("https://") else f"https://{host}"
    return ""


# ---------------------------------------------------------------- 読み取り

APP_ENV = _optional("APP_ENV", "development")
PORT = _int("PORT", 8001)
MEDIA_PORT = _int("MEDIA_PORT", PORT)

PUBLIC_BASE_URL = _optional("PUBLIC_BASE_URL") or _platform_https_origin()
if not PUBLIC_BASE_URL:
    _problems.append(
        "PUBLIC_BASE_URL が設定されていません — Twilio から届く公開 URL。"
        "ローカルでは cloudflared / ngrok のトンネル URL を入れる"
    )
PUBLIC_WSS_URL = _optional("PUBLIC_WSS_URL", PUBLIC_BASE_URL.replace("https://", "wss://"))

DATABASE_URL = _required("DATABASE_URL", "アプリ用。RLS が効く kaden_app で接続する")
REDIS_URL = _optional("REDIS_URL", "redis://localhost:6379/0")

# ★ api（Spring Boot）と同一の値でなければならない。ポリグロット構成で
#   最も踏みやすいのがここで、片方で生成し直すと
#   「api では通るのに voice で 401」という切り分けにくい壊れ方をする。
JWT_SECRET = _required("JWT_SECRET", "api と同じ値。openssl rand -hex 32")

# ---------------------------------------------------------------- Twilio

TWILIO_ACCOUNT_SID = _optional("TWILIO_ACCOUNT_SID")
TWILIO_AUTH_TOKEN = _optional("TWILIO_AUTH_TOKEN")
TWILIO_CALLER_ID = _optional("TWILIO_CALLER_ID")
TWILIO_API_KEY_SID = _optional("TWILIO_API_KEY_SID")
TWILIO_API_KEY_SECRET = _optional("TWILIO_API_KEY_SECRET")
TWILIO_TWIML_APP_SID = _optional("TWILIO_TWIML_APP_SID")
TWILIO_MACHINE_DETECTION = _optional("TWILIO_MACHINE_DETECTION", "DetectMessageEnd")

_twilio_values = [TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_CALLER_ID]
_twilio_filled = [v for v in _twilio_values if v]

# ★ 全部空なら電話機能を無効にして起動する。開発中や、まだ Twilio の
#   アカウントが用意できていない段階でも画面と DB は触れるようにするため。
TELEPHONY_ENABLED = len(_twilio_filled) == 3

if _twilio_filled and not TELEPHONY_ENABLED:
    # ★ 1 つだけ設定されている状態で起動させない。署名検証だけが無効、
    #   のような中途半端な状態を作らないため
    _problems.append(
        "TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_CALLER_ID は 3 つセットです。"
        "全部設定するか、全部空にしてください（全部空なら電話機能を無効にして起動します）"
    )

# ---------------------------------------------------------------- 音声・AI

ASR_PROVIDER = _optional("ASR_PROVIDER", "null")
ASR_API_KEY = _optional("ASR_API_KEY")
ASR_LANGUAGE = _optional("ASR_LANGUAGE", "ja-JP")
ASR_SAMPLE_RATE = _int("ASR_SAMPLE_RATE", 8000)
SILENCE_THRESHOLD_MS = _int("SILENCE_THRESHOLD_MS", 700)
MIN_UTTERANCE_MS = _int("MIN_UTTERANCE_MS", 800)

LLM_API_KEY = _optional("LLM_API_KEY")
# ★ 既定は最新かつ最も能力の高いモデル。安さのために落とす判断は
#   運用側がすべきもので、既定で勝手に下げない
LLM_MODEL = _optional("LLM_MODEL", "claude-opus-5")
# ★ 出力の上限であって課金額ではない。低く見積もると要約が
#   途中で切れて再試行になり、かえって高くつく
LLM_MAX_TOKENS = _int("LLM_MAX_TOKENS", 16000)

# ---------------------------------------------------------------- 保管

S3_ENDPOINT = _optional("S3_ENDPOINT")
S3_BUCKET = _optional("S3_BUCKET", "kaden-recordings")
S3_REGION = _optional("S3_REGION", "ap-northeast-1")
S3_ACCESS_KEY = _optional("S3_ACCESS_KEY")
S3_SECRET_KEY = _optional("S3_SECRET_KEY")
RECORDING_RETENTION_DAYS = _int("RECORDING_RETENTION_DAYS", 365)

# ---------------------------------------------------------------- 架電の既定値

CALLING_TIMEZONE = _optional("CALLING_TIMEZONE", "Asia/Tokyo")
CALLING_HOURS_START = _time("CALLING_HOURS_START", "09:00")
CALLING_HOURS_END = _time("CALLING_HOURS_END", "20:00")
JOB_INTERVAL_SECONDS = _int("JOB_INTERVAL_SECONDS", 60)

CORS_ORIGIN = _optional("CORS_ORIGIN", "http://localhost:3000")

# ---------------------------------------------------------------- 値の検証

if PUBLIC_BASE_URL:
    if not PUBLIC_BASE_URL.startswith("https://") and APP_ENV != "development":
        _problems.append(
            "PUBLIC_BASE_URL は https:// で始まる必要があります（Twilio の要求）。"
            f"実際の値: {PUBLIC_BASE_URL}"
        )
    if PUBLIC_BASE_URL.endswith("/"):
        # ★ 末尾のスラッシュだけで署名対象の URL がずれ、Webhook が全件 403 になる
        _problems.append(
            "PUBLIC_BASE_URL の末尾にスラッシュがあります。"
            "署名対象の URL がずれ、Webhook が全件 403 になります"
        )

if TWILIO_ACCOUNT_SID and not TWILIO_ACCOUNT_SID.startswith("AC"):
    _problems.append(
        'TWILIO_ACCOUNT_SID は "AC" で始まります。API Key SID（SK...）と'
        f"取り違えていませんか。実際の値の先頭: {TWILIO_ACCOUNT_SID[:8]}…"
    )

if TWILIO_AUTH_TOKEN and TWILIO_AUTH_TOKEN == TWILIO_API_KEY_SECRET:
    # ★ 署名検証に使うのは Auth Token。API Key Secret とは別物
    _problems.append(
        "TWILIO_AUTH_TOKEN と TWILIO_API_KEY_SECRET が同じ値です（別物です）。"
        "Webhook の署名検証には Auth Token を使います"
    )

if TWILIO_CALLER_ID and not TWILIO_CALLER_ID.startswith("+"):
    _problems.append(
        f"TWILIO_CALLER_ID は E.164 形式（+81…）にしてください。実際の値: {TWILIO_CALLER_ID}"
    )

try:
    ZoneInfo(CALLING_TIMEZONE)
except ZoneInfoNotFoundError:
    _problems.append(f"CALLING_TIMEZONE が不正です: {CALLING_TIMEZONE}")

if ASR_PROVIDER not in ("null", "deepgram", "google", "azure"):
    _problems.append(f"ASR_PROVIDER は null / deepgram / google / azure のいずれか: {ASR_PROVIDER}")
if ASR_PROVIDER != "null" and not ASR_API_KEY:
    _problems.append(f"ASR_PROVIDER={ASR_PROVIDER} ですが ASR_API_KEY が空です")

# ---------------------------------------------------------------- 報告

if _problems:
    lines = [
        "",
        "=" * 74,
        f" 起動できません: 設定に {len(_problems)} 件の問題があります",
        "=" * 74,
        "",
        *(f"  - {p}" for p in _problems),
        "",
        "ローカル開発 : voice/.env.example をコピーして voice/.env を作り、値を入れる",
        "コンテナ/PaaS: 環境変数として設定する（.env ファイルは読まれない）",
        "",
        "★ PUBLIC_BASE_URL は Twilio Console に登録した URL と 1 文字も違ってはいけません。",
        "  トンネルの URL が変わったら、この変数と Twilio 側の両方を更新してください。",
        "  片方だけだと署名が一致せず、Webhook が全件 403 になります。",
        "=" * 74,
        "",
    ]
    # 一覧は stderr に直接出す。ただし PaaS はトレースだけを切り出して
    # 「上のログ」が見えなくなることがあるので、例外の message にも含める
    print("\n".join(lines), file=sys.stderr)
    raise RuntimeError(
        f"設定に {len(_problems)} 件の問題があります: " + " / ".join(_problems)
    )


@dataclass(frozen=True)
class CallingWindowDefaults:
    """テナント設定が無いときの既定値。実際の値は tenants テーブルが持つ。"""

    timezone: str = CALLING_TIMEZONE
    start: time = CALLING_HOURS_START
    end: time = CALLING_HOURS_END
    weekdays: frozenset[int] = field(default_factory=lambda: frozenset({1, 2, 3, 4, 5}))


CALLING_DEFAULTS = CallingWindowDefaults()


def public_url(path: str) -> str:
    """署名検証と Twilio へ渡す URL を組み立てる唯一の関数。

    ★ request.url から組み立てない。リバースプロキシの後ろでは http:// や
      内部ホスト名になり、Twilio が署名に使った公開 URL と一致しなくなる。
      ここを 1 箇所に閉じ込めておくと、403 の原因調査がここだけで済む。
    """
    return f"{PUBLIC_BASE_URL.rstrip('/')}{path}"


def public_wss(path: str) -> str:
    return f"{PUBLIC_WSS_URL.rstrip('/')}{path}"
