# ============================================================================
# フロント（Next.js）。
#
# ★ standalone 出力を使う。node_modules を丸ごと運ぶと 500MB 級になり、
#   デプロイのたびに転送で待つことになる。
#
# ★ NEXT_PUBLIC_* はビルド時に埋め込まれる。実行時の環境変数では
#   変わらないので、環境ごとにイメージを作るか、
#   実行時に読む形（サーバー側から配る）に変える必要がある。
#   ここでは前者。ビルド引数で受け取る。
# ============================================================================

FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# ★ ブラウザから叩く URL なので、コンテナ名ではなく公開 URL を渡すこと
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ARG NEXT_PUBLIC_VOICE_BASE_URL=http://localhost:8001
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_VOICE_BASE_URL=$NEXT_PUBLIC_VOICE_BASE_URL
ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1

# ★ 非 root で動かす
RUN addgroup -g 1001 -S nodejs && adduser -S nextjs -u 1001

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000

# ★ standalone は HOSTNAME を bind アドレスに使い、Docker がそこに
#   コンテナ ID を入れる。0.0.0.0 を明示しないと外から繋がらない
ENV HOSTNAME=0.0.0.0 PORT=3000

CMD ["node", "server.js"]
