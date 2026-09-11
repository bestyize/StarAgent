package com.starringbox.staragent

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.starringbox.staragent.core.media.MediaPickers
import com.starringbox.staragent.core.media.MediaSavers

/**
 * 桌面端仅用于开发预览：窗口按手机比例，方便直接看到移动端的真实排版。
 */
fun main() = application {
    // 选图与保存都由平台入口注入——媒体模块本身不认识任何平台
    MediaPickers.install(DesktopMediaPicker())
    MediaSavers.install(DesktopMediaSaver())

    Window(
        onCloseRequest = ::exitApplication,
        title = "StarAgent",
        state = rememberWindowState(size = DpSize(420.dp, 900.dp)),
    ) {
        App()
    }
}
