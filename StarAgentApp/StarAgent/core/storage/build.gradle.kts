import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 本地存储层。
 *
 * 只提供最基础的文件读写，不关心存的是什么——业务侧怎么组织、序列化成什么格式，
 * 都是上层的事。做成"平台注册、上层取用"，和选图/保存同一套模式，
 * 所以这一层同样是零平台依赖的。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.storage"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
}
