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
 * 语音同理，**尚未接入**：`SFSpeechRecognizer` 与 `AVSpeechSynthesizer` 都要在
 * Mac 环境编译验证，这里不塞没验证过的实现。
 *
 * 现在的状态是：没有注入设备能力，模型那一路仍然可用（要配服务商）；
 * 两路都没有时语音入口自动隐藏。
 */
fun MainViewController() = ComposeUIViewController { App() }
