package com.starringbox.staragent

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.starringbox.staragent.core.bootstrap.PlatformInstall
import com.starringbox.staragent.core.bootstrap.install

/**
 * 桌面端仅用于开发预览：窗口按手机比例，方便直接看到移动端的真实排版。
 */
fun main() = application {
    // 这一端所有平台能力一次装齐：本地存储、选图、保存、录音与播放。
    // 装了什么、为什么桌面少两样，都在 core:bootstrap 里。
    PlatformInstall.install()

    Window(
        onCloseRequest = ::exitApplication,
        title = "StarAgent",
        state = rememberWindowState(size = DpSize(420.dp, 900.dp)),
    ) {
        App()
    }
}
