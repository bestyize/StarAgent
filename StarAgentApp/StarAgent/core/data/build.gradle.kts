import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * 数据层：应用状态、模型服务配置、对话流。
 *
 * 接入了真实模型（core:llm），界面层依然只跟这一层打交道。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:llm"))
            api(project(":core:agent-runtime"))
            implementation(project(":core:platform"))
            implementation(project(":core:speech-local"))
            // api：AppState.asrEndpoint() / ttsEndpoint() 返回 SpeechEndpoint
            api(project(":core:speech-cloud"))
            // 实现 core:tools 的三个端口。core:tools 自己不认识 Ktor——
            // 只有这里的 WebPort 实现要发请求，所以 HTTP 依赖只出现在这一侧。
            implementation(project(":core:tools"))
            implementation(libs.ktor.client.core)
            implementation(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 群聊编排是挂起函数，得在测试里驱动协程
            implementation(libs.kotlinx.coroutines.test)
            // `AppWebPort` 的重定向闸门：每一跳的响应要可控，见 WebPortRedirectTest。
            // 只在测试里——生产那条路用真引擎。
            implementation(libs.ktor.client.mock)
        }
    }
}
