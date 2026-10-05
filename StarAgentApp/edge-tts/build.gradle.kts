import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("com.android.kotlin.multiplatform.library") version "9.0.1"
    kotlin("plugin.serialization") version "2.4.10"
    `maven-publish`
}

group = "com.starringbox.edge"
version = "0.1.0"

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.edge.tts"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-client-core:3.5.2")
            implementation("io.ktor:ktor-client-websockets:3.5.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
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
            name.set("Edge TTS Kotlin Multiplatform")
            description.set("Unofficial Kotlin Multiplatform client for Microsoft Edge online text-to-speech.")
            url.set("https://github.com/rany2/edge-tts")
        }
    }
}
