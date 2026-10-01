package com.starringbox.staragent.core.platform.notify

import java.awt.Image
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * 桌面端原生系统通知：基于 AWT SystemTray 弹出操作系统通知气泡。
 * 若系统无图形环境或不支持托盘（如 headless 模式），静默忽略保证不崩溃。
 */
class DesktopNotifier : Notifier {

    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            val tray = SystemTray.getSystemTray()
            val image: Image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val icon = TrayIcon(image, "StarAgent").apply {
                isImageAutoSize = true
            }
            tray.add(icon)
            icon
        }.getOrNull()
    }

    override fun notify(id: String, title: String, content: String) {
        runCatching {
            trayIcon?.displayMessage(title, content, TrayIcon.MessageType.INFO)
        }
    }
}
