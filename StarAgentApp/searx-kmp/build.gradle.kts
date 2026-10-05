import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("com.android.kotlin.multiplatform.library") version "9.0.1"
    kotlin("plugin.serialization") version "2.4.10"
    `maven-publish`
}

group = "com.starringbox.searx"
version = "0.1.0"

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.searx"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonMain.dependencies {
            // api：HttpClient 出现在 SearchEngine 的公开构造参数上，调用方要能自己传引擎。
            api("io.ktor:ktor-client-core:3.5.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            // 每个引擎的解析都用 MockEngine 喂一段固定的响应，不外网、可复现。
            implementation("io.ktor:ktor-client-mock:3.5.2")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.5.2")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.5.2")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.2")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Searx Kotlin Multiplatform")
            description.set("A tiny metasearch library: query several public engines concurrently, merge and deduplicate the results.")
            url.set("https://github.com/searxng/searxng")
        }
    }
}
