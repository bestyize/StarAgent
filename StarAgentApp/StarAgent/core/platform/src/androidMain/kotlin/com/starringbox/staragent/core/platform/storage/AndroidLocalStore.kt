package com.starringbox.staragent.core.platform.storage

import android.content.Context
import java.io.File

/**
 * Android 存储：写在应用私有的 filesDir 下，不申请任何额外权限，
 * 卸载应用时随之清除。
 */
class AndroidLocalStore(context: Context) : LocalStore {

    private val base: File = File(context.filesDir, "staragent").apply { mkdirs() }

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

    /** 沙箱里的绝对路径，形如 `/data/data/<包名>/files/staragent`。 */
    override val rootPath: String get() = base.absolutePath
}
