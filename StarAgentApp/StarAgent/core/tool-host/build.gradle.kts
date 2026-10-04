import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

/**
 * 工具宿主适配：把 core:tools 的端口接到各端真实能力上。
 *
 * - 设备操作 `DevicePort`：Android 无障碍控制器与悬浮控制服务，其余端为空实现
 * - 命令执行 `CommandPort`：桌面走 `sh -lc`，移动端不支持
 * - MCP `McpManagerPort`：桌面走 stdio 子进程，移动端不支持
 * - 权限 `PermissionPort`：Android 运行时权限与系统设置页
 *
 * 和 core:platform 分开，是为了让只想用存储、媒体或语音的模块不必认识工具层。
 */
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.starringbox.staragent.core.toolhost"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:tools"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // api：installAndroidPermissions 的公开签名里有 ActivityResultCaller
            api(libs.androidx.activity)
            implementation(libs.androidx.core.ktx)
        }
    }
}
