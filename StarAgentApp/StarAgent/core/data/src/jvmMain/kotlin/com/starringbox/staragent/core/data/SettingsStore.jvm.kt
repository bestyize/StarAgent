package com.starringbox.staragent.core.data

import java.util.prefs.Preferences

actual object SettingsStore {
    private val prefs: Preferences =
        Preferences.userRoot().node("com/starringbox/staragent")

    actual fun get(key: String): String? = runCatching { prefs.get(key, null) }.getOrNull()

    actual fun put(key: String, value: String) {
        runCatching {
            prefs.put(key, value)
            prefs.flush()
        }
    }

    actual fun remove(key: String) {
        runCatching {
            prefs.remove(key)
            prefs.flush()
        }
    }
}
