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

    /**
     * 写过多少次。
     *
     * 目录树存的是一堆小文件，**只写变了的**才不至于比原来那一份 JSON 更慢——
     * 那件事没法从"最终内容对不对"上看出来，只能数写入次数。
     */
    var textWrites = 0
        private set
    var byteWrites = 0
        private set

    fun resetWriteCounts() {
        textWrites = 0
        byteWrites = 0
    }

    /** 现在存着哪些路径。断言"某棵子树没留下东西"时用。 */
    fun paths(): List<String> = (texts.keys + blobs.keys).sorted()

    override fun readText(name: String): String? = texts[name]

    override fun writeText(name: String, text: String) {
        textWrites++
        texts[name] = text
    }

    override fun readBytes(name: String): ByteArray? = blobs[name]

    override fun writeBytes(name: String, bytes: ByteArray) {
        byteWrites++
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
