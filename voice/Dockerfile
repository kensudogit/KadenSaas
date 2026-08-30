# ============================================================================
# voice サービス（FastAPI）。webhook / media / jobs を 1 イメージで賄う。
#
# ★ マイグレーションを持たない。スキーマの所有者は api（Flyway）。
#   ここに migrate コマンドを足さないこと。2 箇所から schema を変えると
#   「片方だけ適用された状態」が生まれる。
# ============================================================================

# ---------------------------------------------------------------- builder
FROM python:3.12-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1

WORKDIR /build

# ★ 依存の解決を先に行い、レイヤをキャッシュさせる。
#   app/ を先に COPY するとコード変更のたびに全依存を入れ直すことになる
COPY voice/pyproject.toml ./
COPY voice/app ./app

RUN python -m venv /opt/venv  && /opt/venv/bin/pip install --upgrade pip setuptools wheel  && /opt/venv/bin/pip install ".[storage,llm]"

# ---------------------------------------------------------------- runtime
FROM python:3.12-slim AS runtime

ENV PYTHONUNBUFFERED=1     PYTHONDONTWRITEBYTECODE=1     PATH="/opt/venv/bin:$PATH"     APP_ENV=production

# ★ 非 root で動かす。録音（個人情報）を扱うコンテナなので、
#   侵入されたときにできることを減らす
RUN groupadd --system app && useradd --system --gid app --home /srv app

COPY --from=builder /opt/venv /opt/venv

WORKDIR /srv
COPY voice/app ./app
COPY voice/docker-entrypoint.sh /usr/local/bin/entrypoint

RUN chmod +x /usr/local/bin/entrypoint && chown -R app:app /srv

USER app
EXPOSE 8001

# ★ 「プロセスが生きている」ではなく「DB に繋がる」で判定する。
#   前者だと DB 断でもロードバランサに入れられ続け、
#   webhook が全部 500 になってから気付くことになる
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3   CMD python -c "import os,urllib.request,sys; port=os.environ.get('PORT','8001'); sys.exit(0 if urllib.request.urlopen(f'http://127.0.0.1:{port}/healthz', timeout=4).status==200 else 1)"

ENTRYPOINT ["entrypoint"]
CMD ["web"]
