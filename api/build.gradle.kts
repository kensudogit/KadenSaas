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
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // @RequestParam などの引数名をリフレクションで読むため
    options.compilerArgs.add("-parameters")
}
