plugins {
    // 手元は JDK 23。toolchain が 21 を要求するので Foojay から自動取得する
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "kaden-api"
