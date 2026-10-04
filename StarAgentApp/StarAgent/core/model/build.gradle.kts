import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 领域模型层：纯 Kotlin，不依赖 Compose、不依赖平台。
 * 所有功能模块共享这里的类型定义。
 *
 * `MediaAsset` 也在这里：图片的编码规则跟着消息体走，是领域类型不是平台能力。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.model"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api：任务的下一次执行时刻要在数据层和界面层都用
            api(libs.kotlinx.datetime)
            // api：MediaAsset 是本模块的公开类型且标了 @Serializable，
            // 用它序列化的上层模块要看得到 KSerializer 这些类型
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 测试要构造 JsonObject 形状的 inputSchema，序列化 JSON 在测试里直接用到
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
