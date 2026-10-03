package com.starringbox.staragent.core.data

import android.content.Context
import android.content.SharedPreferences

/** Android 上需要 Context 才能拿到 SharedPreferences，由入口 Activity 注入。 */
object AndroidAppContext {
    private var application: Context? = null

    fun install(context: Context) {
        application = context.applicationContext
    }

    internal fun preferences(): SharedPreferences? =
        application?.getSharedPreferences("staragent", Context.MODE_PRIVATE)
}

actual object SettingsStore {
    actual fun get(key: String): String? {
        val raw = AndroidAppContext.preferences()?.getString(key, null) ?: return null
        return if (AndroidSecureStorage.isSensitive(key)) {
            val decrypted = AndroidSecureStorage.decrypt(raw)
            // 如果读取到历史明文，且加密成功产生新密文，透明升级写回
            if (decrypted.isNotBlank() && !raw.startsWith("enc:v1:")) {
                put(key, decrypted)
            }
            decrypted
        } else {
            raw
        }
    }

    actual fun put(key: String, value: String) {
        val storedValue = if (AndroidSecureStorage.isSensitive(key)) {
            AndroidSecureStorage.encrypt(value)
        } else {
            value
        }
        AndroidAppContext.preferences()?.edit()?.putString(key, storedValue)?.apply()
    }

    actual fun remove(key: String) {
        AndroidAppContext.preferences()?.edit()?.remove(key)?.apply()
    }
}
