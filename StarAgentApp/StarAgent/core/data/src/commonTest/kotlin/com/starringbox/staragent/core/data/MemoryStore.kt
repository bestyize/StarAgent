package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.storage.LocalStore

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
}
