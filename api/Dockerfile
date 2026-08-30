# ============================================================================
# api サービス（Spring Boot）。業務 API とスキーマの所有者。
#
# ★ 起動時に Flyway が走る。voice より先に立ち上げること
#   （docker-compose の depends_on で順序を固定してある）。
#
# ★ Twilio SDK は入っていない。電話に触れるのは voice だけ、という
#   境界を依存関係のレベルで守る。scripts/check-boundaries.sh が検証する。
# ============================================================================

# ---------------------------------------------------------------- builder
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# ★ wrapper と依存宣言だけを先に入れて、依存の解決をキャッシュさせる
COPY api/gradlew ./
COPY api/gradle ./gradle
COPY api/build.gradle.kts api/settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY api/src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre AS runtime

ENV APP_ENV=production

# ★ 非 root。顧客情報と通話記録を扱うので、権限は最小にする
RUN groupadd --system app && useradd --system --gid app --home /srv app

WORKDIR /srv
COPY --from=builder /build/build/libs/*.jar app.jar
RUN chown -R app:app /srv

USER app
EXPOSE 8080

# ★ actuator の health は DB の状態を含む。プロセスの生死だけで
#   判定すると、DB 断のインスタンスにトラフィックが入り続ける
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3   CMD ["sh", "-c", "curl -fsS http://127.0.0.1:${PORT:-8080}/actuator/health || exit 1"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
