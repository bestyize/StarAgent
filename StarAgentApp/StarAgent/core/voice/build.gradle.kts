import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 语音能力层。
 *
 * 只定契约与规则，不含任何实现：
 * - **语音转文字**（[Transcriber]）与**文字转语音**（[Synthesizer]）各是一条接口，
 *   底下可以是设备自带的能力（Android 的系统识别与合成，不要 Key），
 *   也可以是某个大模型服务商（HTTP）；
 * - 录音（[Recorder]）与播放（[Player]）是设备能力，由各端入口注入。
 *
 * 不认识 Compose，不认识任何模型协议，也不认识任何一个具体服务商——
 * 上层只调 [Voice] 上的那几个方法。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.voice"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Voice 的选取逻辑是挂起函数，测试里要驱动协程
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
