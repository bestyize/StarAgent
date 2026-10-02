package com.starringbox.staragent.core.speech.local

import android.content.Context

/** 原生模型只持有 applicationContext，避免依赖数据层或保留 Activity。 */
object LocalSpeechContext {
    private var installed: Context? = null
    fun install(context: Context) { installed = context.applicationContext }
    fun context(): Context? = installed
}
