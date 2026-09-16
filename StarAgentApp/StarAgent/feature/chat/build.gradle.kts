import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** 功能模块：对话——消息流、内联卡片、输入坞。 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.feature.chat"
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
            // 选图、保存、语音这三条都要显式声明。以前 media 是搭 core:model 的
            // api 传递过来的，MediaAsset 搬进 core:model 之后那条链就没了。
            implementation(project(":core:platform"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }

        // 只测不依赖界面的那部分：按住说话的松手判断（holdOutcome）。
        // 手势本身要有屏幕才能跑，那部分靠真机验证。
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
