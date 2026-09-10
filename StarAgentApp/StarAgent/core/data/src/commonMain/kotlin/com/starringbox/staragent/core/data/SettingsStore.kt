package com.starringbox.staragent.core.data

/**
 * 极简的本地键值存储，用来记住模型服务配置。
 *
 * 这是整个一期里唯一需要平台代码的地方：桌面用 java.util.prefs、
 * Android 用 SharedPreferences、iOS 用 NSUserDefaults。
 * 只存字符串，不做任何业务。
 */
expect object SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
