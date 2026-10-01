package com.starringbox.staragent.core.platform

import java.awt.Desktop
import java.net.URI

actual fun platformOpenUrl(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
    Desktop.getDesktop().browse(URI(url))
    true
}.getOrDefault(false)
