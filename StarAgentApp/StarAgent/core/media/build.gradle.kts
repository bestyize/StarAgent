import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 多模态基础能力层。
 *
 * 职责边界划得很死：**只负责"拿到一张图，并把它变成可传输的形式"**。
 * - 不认识 Compose，也不认识任何模型协议
 * - 不关心图是发去对话、发去任务、还是只做本地存储
 *
 * 上层（core:llm 的协议层、feature:chat 的界面）各自按需取用，不重复实现编码与校验。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.media"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // MediaAsset 要可序列化，@Serializable 注解来自这个库
            api(libs.kotlinx.serialization.json)
        }
    }
}
