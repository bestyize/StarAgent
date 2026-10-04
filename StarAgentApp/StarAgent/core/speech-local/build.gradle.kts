import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "com.starringbox.staragent.core.speech.local"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:speech-contracts"))
            implementation(libs.kotlinx.coroutines.core)
        }
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)
        listOf(jvmMain.get(), androidMain.get()).forEach { it.resources.srcDir("src/sharedResources") }
        androidMain.dependencies {
            implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-static-link-onnxruntime:1.13.8@aar")
        }
        jvmMain.dependencies {
            implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:1.13.8")
            // 打包目标可覆盖构建主机，避免在 Windows 上构建其它平台时带错 native。
            val targetOs = providers.gradleProperty("speech.targetOs").orElse(System.getProperty("os.name")).get().lowercase()
            val targetArch = providers.gradleProperty("speech.targetArch").orElse(System.getProperty("os.arch")).get().lowercase()
            val os = when {
                targetOs.contains("windows") || targetOs == "win" -> "win"
                targetOs.contains("linux") -> "linux"
                targetOs.contains("mac") || targetOs == "osx" -> "osx"
                else -> null
            }
            val arch = when (targetArch) {
                "amd64", "x86_64", "x64" -> "x64"
                // sherpa-onnx 的产物命名不统一：Windows 叫 arm64，macOS / Linux 叫 aarch64。
                "aarch64", "arm64" -> if (os == "win") "arm64" else "aarch64"
                else -> null
            }
            if (os != null && arch != null) {
                implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-native-lib-$os-$arch:1.13.8")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
