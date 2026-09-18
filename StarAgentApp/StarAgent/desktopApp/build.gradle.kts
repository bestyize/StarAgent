import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    // 各端平台能力由装配层一次装齐；core:platform 由它带进来
    implementation(project(":core:bootstrap"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.starringbox.staragent.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // 这是用户看到的名字（Finder 里的 StarAgent.app、DMG 的卷名），
            // 反 DNS 那串是 bundleID 的事，两者不该混用。
            packageName = "StarAgent"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "com.starringbox.staragent"

                // Hardened Runtime 的权限清单，麦克风那条在里面。
                // 为什么非要自己给一份：jpackage 在 arm64 上一定会签名，而且带 runtime 标记，
                // 于是 Hardened Runtime 生效——受保护资源没在这里声明就是直接拒绝，
                // 连授权框都不弹。它默认那份只有三条 JVM 的，没有麦克风。
                entitlementsFile.set(project.file("entitlements.plist"))

                // 麦克风用途说明是硬要求，不是文案。
                // 语音输入的采集是我们自己做的（javax.sound 的 TargetDataLine），
                // Info.plist 里没有这条，macOS 不会弹授权框——
                // 结果不是"用户拒绝了"，而是录音静默拿不到数据，看起来像识别坏了。
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>把你说的话转成文字，用来语音输入。</string>
                    """.trimIndent()
                }
            }
        }
    }
}