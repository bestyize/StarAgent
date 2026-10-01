import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 平台能力层：**本地存储、选图/保存、语音**。
 *
 * 这三样原本是三个模块（core:storage / core:media / core:voice），合成一个，
 * 因为它们是同一种东西——"这台设备能做什么"，只是领域不同：
 *
 * - `core.platform.storage` 按名字读写文本与字节
 * - `core.platform.media`   选图与保存
 * - `core.platform.voice`   录音、播放、语音识别、语音合成
 *
 * **包名保留了三段**，没有拍平成一个 `core.platform.*`：领域边界还在，
 * 只是不再用 Gradle 模块去切分——每多一个模块就多一份构建文件，
 * 而这三样是同时长出来的、也总是一起被各端入口装进去的。
 *
 * 各端的实现放在各自的源集里（androidMain / jvmMain / iosMain），
 * commonMain 只有契约与规则。每一类都照"接口 + 私有兜底 + 注册表"的形状写，
 * 具体理由见 `Recorder.kt` 的注释。
 *
 * `core:model` 是唯一的依赖：`MediaAsset` 是领域类型，住在那边，
 * 所以这里不再是"最底层"——它上面是 model，下面是各端。
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
            implementation(project(":core:tools"))
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
        // jvmMain：纯 JDK（javax.sound.sampled），不需要额外依赖
        // iosMain：用 Kotlin/Native 自带的 platform.* klib，不需要额外依赖
    }
}
