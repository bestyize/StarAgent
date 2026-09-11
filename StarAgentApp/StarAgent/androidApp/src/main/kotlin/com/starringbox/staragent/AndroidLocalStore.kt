package com.starringbox.staragent

import android.content.Context
import com.starringbox.staragent.core.storage.LocalStore
import java.io.File

/**
 * Android 存储：写在应用私有的 filesDir 下，不申请任何额外权限，
 * 卸载应用时随之清除。
 */
class AndroidLocalStore(context: Context) : LocalStore {

    private val base: File = File(context.filesDir, "staragent").apply { mkdirs() }

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
