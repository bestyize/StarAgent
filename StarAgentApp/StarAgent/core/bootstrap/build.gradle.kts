import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 装配层：把各端的平台能力一次装起来。
 *
 * 之所以单独一层，是因为"实现"和"什么时候把实现装上去"是两件事：
 * `core:platform` 负责前者（各端实现放在自己的源集里），这一层负责后者。
 * 好处是各端入口只剩一行——尤其是 Android，它要传 `Context` 和
 * `ActivityResultCaller`，散在入口里很容易漏掉其中一个或顺序写错。
 *
 * 依赖方向：bootstrap → core:data（要 AndroidAppContext）→ core:platform。
 * 装在应用最外层，别的模块都不依赖它。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.bootstrap"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
            implementation(project(":core:tool-host"))
            implementation(project(":core:speech-local"))
        }
        androidMain.dependencies {
            // 只有 Android 用得到：AndroidAppContext 要把 Context 交给 core:data，
            // 好让 SettingsStore 能落到 SharedPreferences。
            implementation(project(":core:data"))
        }
    }
}
