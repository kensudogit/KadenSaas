// ============================================================================
// 業務 API（Spring Boot）。
//
// ★ Twilio の SDK をここに入れない。発信の関門を 1 箇所に保つため、
//   電話に触れるのは voice サービス（FastAPI）だけ、という境界を
//   依存関係のレベルで守る。scripts/check-boundaries.sh がこれを検証する。
//   依存に twilio が現れた時点で、その境界は壊れている。
//
// ★ Gradle は wrapper で 8.12 に固定してある。Spring Boot 3.4 の
//   Gradle プラグインは Gradle 9 をまだ受け付けないため。
//   ./gradlew を使えば、手元に何が入っていても同じ結果になる。
// ============================================================================

plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.kadensaas"
version = "0.1.0"

// ★ JUnit を Spring Boot 3.4.1 の既定（5.11.4 / platform 1.11.4）から上げる。
//
//   VS Code の Test Runner for Java（vscjava.vscode-java-test 0.46.0）は
//   Eclipse JDT の JUnit5 ランナーを使い、同梱の junit-platform 1.14.4 で
//   コンパイルされている。org.junit.platform.engine.OutputDirectoryCreator は
//   platform 1.14 で入ったクラス（1.13 までは
//   engine.reporting.OutputDirectoryProvider という別の名前）なので、
//   プロジェクト側が 1.13 以下だと ▷ から実行した瞬間に
//     NoClassDefFoundError: org/junit/platform/engine/OutputDirectoryCreator
//   で落ちる。gradlew test は自前の launcher を使うので通ってしまい、
//   「CI は緑なのにエディタからだけ落ちる」という切り分けにくい形で出る。
//
//   ★ 1.13 では足りない。拡張が同梱する 1.14.4 に合わせること。
//     Boot の BOM は junit-bom をこのプロパティで引くので、
//     jupiter と platform が揃って上がる（5.14.4 → platform 1.14.4）。
extra["junit-jupiter.version"] = "5.14.4"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // ★ スキーマの所有者はこのサービス。voice 側はマイグレーションを持たない
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ★ E.164 正規化。voice 側の Python phonenumbers と同じ規則で揃える。
    //   ずれると DNC の照合が静かに素通りする
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.52")

    implementation("com.stripe:stripe-java:28.2.0")

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    testCompileOnly("org.projectlombok:lombok:1.18.36")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // ★ RLS は本物の PostgreSQL でしか検証できない。H2 では意味がない
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")

    // ★ launcher を明示する。書かないと Gradle 8.12 が自前で古い
    //   junit-platform-launcher を混ぜ、engine（1.13.4）と食い違って
    //     JUnitException: OutputDirectoryProvider not available
    //   で discover の段階から落ちる。junit-bom が揃えるのでバージョンは書かない
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // @RequestParam などの引数名をリフレクションで読むため
    options.compilerArgs.add("-parameters")
}
