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
            implementation(project(":core:storage"))
            implementation(project(":core:voice"))
            implementation(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 群聊编排是挂起函数，得在测试里驱动协程
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
