import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 模型接入层：把一次对话请求发给大模型服务，并把流式响应解析成事件。
 *
 * 这一层只认识协议，不认识界面；也不含任何 Compose 依赖。
 * 只有 HTTP 引擎是按平台分的，其余全在 commonMain。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.llm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:media"))
            // 语音的模型后端在这一层：它要用同一套 HTTP 引擎和错误文案
            implementation(project(":core:voice"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
