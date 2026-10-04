import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** 功能模块：我的——模型服务、IM 渠道、记忆、技能。 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.feature.settings"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:designsystem"))
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            // 文件浏览器要直接问设备"能存哪些东西"（LocalStores.list / size / rootPath）。
            // core:data 用的是 implementation，传不过来，所以这里得显式写一条——
            // 和 feature:chat 里那条注释是同一个坑。
            implementation(project(":core:platform"))
            implementation(project(":core:tool-host"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
    }
}
