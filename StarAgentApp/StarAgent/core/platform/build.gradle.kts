import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 平台能力层：**本地存储、选图/保存、设备语音、通知、定时唤醒**。
 *
 * 这些是同一种东西——"这台设备能做什么"，只是领域不同：
 *
 * - `core.platform.storage`  按名字读写文本与字节
 * - `core.platform.media`    选图与保存
 * - `core.platform.voice`    设备录放、系统识别与合成、三路语音的选取
 * - `core.platform.notify`   系统通知
 * - `core.platform.schedule` 后台定时唤醒
 *
 * **包名按领域分段**，没有拍平成一个 `core.platform.*`：领域边界还在，
 * 只是不再用 Gradle 模块去切分——它们总是一起被各端入口装进去的。
 *
 * 工具端口（设备操作 / 命令 / MCP / 权限）的各端实现不在这里，在 core:tool-host：
 * 这一层不认识 core:tools。
 *
 * 各端的实现放在各自的源集里（androidMain / jvmMain / iosMain），
 * commonMain 只有契约与规则。每一类都照"接口 + 私有兜底 + 注册表"的形状写，
 * 具体理由见 `Recorder.kt` 的注释。
 *
 * 依赖只有 `core:model`（`MediaAsset` 是领域类型，住在那边）与 `core:speech-contracts`。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.platform"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api：MediaAsset 出现在 pickImage / save 的公开签名里
            api(project(":core:model"))
            // api：Voice 的公开签名里有 SpeechOutcome / Transcriber 等契约类型
            api(project(":core:speech-contracts"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Voice 的选取逻辑是挂起函数，测试里要驱动协程
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // ActivityResultCaller 出现在 DeviceVoice.install 的**公开签名**里，
            // 各端入口编译时要看得到这个类型，所以是 api 不是 implementation。
            api(libs.androidx.activity)
            implementation(libs.androidx.core.ktx)
            // AndroidSystemSynthesizer 的 ready 靠快照状态驱动重组，见那个类的注释。
            // 必须用 catalog 里这一个源：换成别处来的 compose runtime，
            // 快照就不互通，重组会**静默**失效（不报编译错），表现为朗读按钮一直不出现。
            implementation(libs.compose.runtime)
        }
        jvmMain.dependencies {
            // Edge TTS 的公开服务只返回 CBR MP3；JDK 自带的采样系统不解码 MP3。
            implementation("javazoom:jlayer:1.0.1")
        }
        // iosMain：用 Kotlin/Native 自带的 platform.* klib，不需要额外依赖
    }
}
