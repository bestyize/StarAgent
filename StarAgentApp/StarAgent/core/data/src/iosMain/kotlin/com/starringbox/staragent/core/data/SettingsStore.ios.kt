package com.starringbox.staragent.core.data

import platform.Foundation.NSUserDefaults

actual object SettingsStore {
    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? = defaults.stringForKey(key)

    actual fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
