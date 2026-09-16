package com.starringbox.staragent.core.bootstrap

import android.content.Context
import androidx.activity.result.ActivityResultCaller
import com.starringbox.staragent.core.data.AndroidAppContext
import com.starringbox.staragent.core.platform.media.AndroidMediaPicker
import com.starringbox.staragent.core.platform.media.AndroidMediaSaver
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaSavers
import com.starringbox.staragent.core.platform.storage.AndroidLocalStore
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.DeviceVoice
import com.starringbox.staragent.core.platform.voice.install

/**
 * Android：把这一端的能力一次装齐。
 *
 * **必须在 `Activity.onCreate` 里调**，而且要在 `super.onCreate` 之前——
 * 选图、保存、录音、识别四样都要 `registerForActivityResult`，它只在 Activity
 * 初始化阶段合法。装晚了会抛 `IllegalStateException`。
 *
 * `context` 传 `applicationContext`：这些实现只拿它读权限、开 `AudioRecord`、
 * 取 `cacheDir`、读写 SharedPreferences，都不需要 UI 上下文；传 Activity 只会让
 * 它们各持一个 Activity 引用。`caller` 则必须是 Activity 本身——注册进去的回调
 * 活得比这些对象长。
 *
 * 顺序有讲究，别调换：`AndroidAppContext` 必须最先装，因为 `core:data` 的
 * `SettingsStore` 要靠它才能落到 SharedPreferences，而 `AppState` 一构造就要读配置。
 */
fun PlatformInstall.install(context: Context, caller: ActivityResultCaller) {
    // 1. 最先：让 core:data 拿得到 Context
    AndroidAppContext.install(context)

    // 2. 本地存储、选图、保存
    LocalStores.install(AndroidLocalStore(context))
    MediaPickers.install(AndroidMediaPicker(context, caller))
    MediaSavers.install(AndroidMediaSaver(context, caller))

    // 3. 语音：录音、播放、系统识别、系统合成（细节和约束见 DeviceVoice 的注释）
    DeviceVoice.install(context, caller)
}
