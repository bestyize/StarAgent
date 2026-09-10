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
    actual fun get(key: String): String? =
        AndroidAppContext.preferences()?.getString(key, null)

    actual fun put(key: String, value: String) {
        AndroidAppContext.preferences()?.edit()?.putString(key, value)?.apply()
    }

    actual fun remove(key: String) {
        AndroidAppContext.preferences()?.edit()?.remove(key)?.apply()
    }
}
