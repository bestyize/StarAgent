package com.starringbox.staragent.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

private var browserContext: Context? = null

fun installAndroidBrowser(context: Context) {
    browserContext = context.applicationContext
}

actual fun platformOpenUrl(url: String): Boolean = runCatching {
    val context = browserContext ?: return false
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrDefault(false)
