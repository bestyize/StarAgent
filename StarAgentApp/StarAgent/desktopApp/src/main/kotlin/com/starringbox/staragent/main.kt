package com.starringbox.staragent

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.starringbox.staragent.core.media.MediaPickers
import com.starringbox.staragent.core.media.MediaSavers
import com.starringbox.staragent.core.storage.LocalStores
import com.starringbox.staragent.core.voice.Players
import com.starringbox.staragent.core.voice.Recorders

/**
 * 桌面端仅用于开发预览：窗口按手机比例，方便直接看到移动端的真实排版。
 */
fun main() = application {
    // 选图、保存、本地存储都由平台入口注入——这几个核心模块本身都不认识平台
    LocalStores.install(DesktopLocalStore())
    MediaPickers.install(DesktopMediaPicker())
    MediaSavers.install(DesktopMediaSaver())

    // 语音这边桌面只装录音和播放：JDK 没有系统级的识别与合成，
    // 那两件事走用户配的服务商（模型后端由 core:data 装）。
    Recorders.install(DesktopVoiceRecorder())
    Players.install(DesktopVoicePlayer())

    Window(
        onCloseRequest = ::exitApplication,
        title = "StarAgent",
        state = rememberWindowState(size = DpSize(420.dp, 900.dp)),
    ) {
        App()
    }
}
