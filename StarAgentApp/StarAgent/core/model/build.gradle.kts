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
 * 依赖 :core:platform 是因为消息体里要能承载图片——
 * 媒体资源本身（含编码规则）统一由那一层定义，这里只引用，不重复造。
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
    }
}
