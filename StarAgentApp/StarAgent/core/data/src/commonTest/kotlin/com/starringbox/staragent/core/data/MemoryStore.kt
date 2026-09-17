package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.platform.storage.LocalStore

/**
 * 内存版存储，测试里替代平台实现。
 *
 * 用真的 `java.util.prefs` / SharedPreferences 会有副作用——比如把开发机上
 * 用户真实配好的 API Key 冲掉——所以凡是碰 `LocalStores` 的测试都装这一份。
 */
internal class MemoryStore : LocalStore {
    private val texts = mutableMapOf<String, String>()
    private val blobs = mutableMapOf<String, ByteArray>()

    override fun readText(name: String): String? = texts[name]

    override fun writeText(name: String, text: String) {
        texts[name] = text
    }

    override fun readBytes(name: String): ByteArray? = blobs[name]

    override fun writeBytes(name: String, bytes: ByteArray) {
        blobs[name] = bytes
    }

    override fun delete(name: String) {
        texts.remove(name)
        blobs.remove(name)
    }

    /** 和真实实现一样：递归列出，返回相对根目录的路径。 */
    override fun list(prefix: String): List<String> {
        val p = prefix.trimEnd('/')
        return (texts.keys + blobs.keys)
            .filter { p.isEmpty() || it == p || it.startsWith("$p/") }
            .distinct()
            .sorted()
    }

    override fun size(name: String): Long? =
        texts[name]?.encodeToByteArray()?.size?.toLong() ?: blobs[name]?.size?.toLong()

    /** 内存里没有"目录"这回事——正好用来测"没有根路径可显示"那条分支。 */
    override val rootPath: String? get() = null
}
