import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 语音契约：识别 / 合成 / 录音 / 播放的接口、音频格式（PCM、WAV）与录音准入规则。
 *
 * 纯 Kotlin，不依赖任何平台、网络或 UI。设备实现在 core:platform，
 * 云端实现在 core:speech-cloud，离线实现在 core:speech-local，三者只经由这里对齐。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.speech"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
