import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))
    // 各端平台能力由装配层一次装齐；core:data / core:platform 都由它带进来
    implementation(project(":core:bootstrap"))
    // Manifest 直接引用 core:platform 的 BroadcastReceiver；声明直接依赖让 Android Lint 能解析到类。
    implementation(project(":core:platform"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// 测试签名：**上线前换成正式密钥**。仓库里放的是自签测试 keystore，
// 密码写死在这里，方便任何人直接打出可安装的包。
val releaseStoreFile = file("staragent-test.jks")
val releaseStorePassword = "staragent-test"
val releaseKeyAlias = "staragent"
val releaseKeyPassword = "staragent-test"

android {
    namespace = "com.starringbox.staragent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.starringbox.staragent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release").apply {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        // debug 和 release 都用同一把测试密钥，两个包可以互相覆盖安装。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}