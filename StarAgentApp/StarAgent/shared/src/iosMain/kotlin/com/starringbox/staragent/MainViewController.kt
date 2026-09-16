package com.starringbox.staragent

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS 入口。
 *
 * ⚠️ **选图能力尚未接入。**
 *
 * PHPickerViewController 需要走 delegate 回调，在 Kotlin/Native 侧写起来牵连较多，
 * 而当前环境是 Windows，编译不到 iOS 目标——塞一个没验证过的实现比留空更糟。
 *
 * 现在的状态是：没有注入实现，`MediaPickers.available` 保持 false，
 * 对话页的相机入口会自动隐藏（不会出现点了没反应的死按钮），文字功能一切正常。
 *
 * 接入时要做的只有一步——在这里调用 `MediaPickers.install(IosMediaPicker())`，
 * 其余代码（协议、数据、界面）都不用动，因为选图本来就是按契约注入的。
 */
/**
 * 语音：设备实现**已经写好了**，在 `core:voice` 的 `iosMain` 里
 * （`IosVoiceRecorder` / `IosVoicePlayer` / `IosSystemTranscriber` / `IosSystemSynthesizer`），
 * 由 `DeviceVoice.install()` 一次装齐。这里还没调用它——给 `shared` 加上
 * `implementation(project(":core:platform"))` 之后补一行即可。
 *
 * ⚠️ 在那之前，iOS 上的语音是**整个不可用**的，不是"只差设备那一路"。
 * 模型那一路同样起不来：`ModelTranscriber.ready` 要求 `Recorders.available`、
 * `ModelSynthesizer.ready` 要求 `Players.available`，而这两个在没装之前恒为 false。
 * 所以光配服务商不会让麦克风出现——这点容易想当然，特意写明。
 *
 * ⚠️ 接上之前，`Info.plist` 必须先加 `NSSpeechRecognitionUsageDescription` 与
 * `NSMicrophoneUsageDescription` 两条用途说明，少了不是功能失效，是一调用就崩。
 */
fun MainViewController() = ComposeUIViewController { App() }
