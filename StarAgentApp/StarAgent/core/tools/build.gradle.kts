import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 工具执行层：契约 + 注册表 + 内置工具。
 *
 * **只依赖 core:model**——不认识 AppState、不认识 Compose、不认识 Ktor。
 * 工具要能力就走 ports 里的窄接口，由 core:data 实现并注入。
 * 这是每个工具都能用假端口单测的前提，也是将来接外部工具的接缝。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.tools"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 工具的 execute 是挂起函数，得在测试里驱动协程
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
