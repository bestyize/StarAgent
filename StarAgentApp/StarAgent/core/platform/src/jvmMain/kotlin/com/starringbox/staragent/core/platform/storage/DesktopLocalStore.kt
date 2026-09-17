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

    private fun file(name: String): File =
        File(base, StorePaths.normalize(name)).apply { parentFile?.mkdirs() }

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

    /** 递归列出、按路径排序。**不抛**——它会用在启动路径上，那里宁可少读也不能崩。 */
    override fun list(prefix: String): List<String> = runCatching {
        val dir = if (prefix.isBlank()) base else File(base, StorePaths.normalize(prefix))
        if (!dir.isDirectory) return@runCatching emptyList()
        dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).invariantSeparatorsPath }
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

    override fun size(name: String): Long? = file(name).takeIf { it.isFile }?.length()

    /** 形如 `C:\Users\<用户>\.staragent`。 */
    override val rootPath: String get() = base.absolutePath
}
