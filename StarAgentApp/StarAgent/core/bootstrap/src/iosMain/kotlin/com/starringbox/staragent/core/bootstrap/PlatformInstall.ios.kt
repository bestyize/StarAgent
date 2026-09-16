package com.starringbox.staragent.core.bootstrap

import com.starringbox.staragent.core.platform.voice.DeviceVoice
import com.starringbox.staragent.core.platform.voice.install

/**
 * iOS：目前只装语音，**而且还没有人调它**。
 *
 * 语音那一套（`IosVoiceRecorder` / `IosVoicePlayer` / `IosSystemTranscriber` /
 * `IosSystemSynthesizer`）已经写在 `core:platform` 的 `iosMain` 里，能通过编译，
 * 但没在真机上跑过——开发机是 Windows，Kotlin/Native 只能编 klib，链接和测试都要 macOS。
 *
 * 选图与保存还没有 iOS 实现（`MediaPickers.available` 会保持 false，相机入口自动隐藏，
 * 不会出现点了没反应的死按钮）。
 *
 * ## 接上时要做的两步
 *
 * 1. 在 `shared/src/iosMain/.../MainViewController.kt` 里调 `PlatformInstall.install()`；
 * 2. 给 `shared` 加一条 `implementation(project(":core:bootstrap"))`。
 *
 * ⚠️ 同时 **`Info.plist` 必须加 `NSSpeechRecognitionUsageDescription` 与
 * `NSMicrophoneUsageDescription`**，少了不是功能失效，是一调用就崩。
 *
 * ⚠️ 在那之前，iOS 上的语音是**整个不可用**的，不是"只差设备那一路"：
 * 模型那一路同样起不来，因为 `ModelTranscriber.ready` / `ModelSynthesizer.ready`
 * 都要求 `Recorders.available` / `Players.available`，而没装之前恒为 false。
 */
fun PlatformInstall.install() {
    DeviceVoice.install()
}
