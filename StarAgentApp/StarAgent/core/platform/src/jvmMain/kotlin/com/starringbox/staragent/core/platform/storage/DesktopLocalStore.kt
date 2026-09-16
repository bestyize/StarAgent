package com.starringbox.staragent.core.platform.storage

import java.io.File

/**
 * 桌面端存储：写在用户目录下的 `.staragent/` 里。
 * 数据在应用之外也能看到，调试时方便。
 */
class DesktopLocalStore(
    root: File = File(System.getProperty("user.home"), ".staragent"),
) : LocalStore {

    private val base: File = root.apply { mkdirs() }

    private fun file(name: String): File = File(base, name).apply { parentFile?.mkdirs() }

    override fun readText(name: String): String? =
        file(name).takeIf { it.isFile }?.readText()

    override fun writeText(name: String, text: String) {
        file(name).writeText(text)
    }

    override fun readBytes(name: String): ByteArray? =
        file(name).takeIf { it.isFile }?.readBytes()

    override fun writeBytes(name: String, bytes: ByteArray) {
        file(name).writeBytes(bytes)
    }

    override fun delete(name: String) {
        file(name).delete()
    }
}
