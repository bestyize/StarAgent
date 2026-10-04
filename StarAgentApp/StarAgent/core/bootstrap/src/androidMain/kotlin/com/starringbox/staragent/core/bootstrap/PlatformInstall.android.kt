package com.starringbox.staragent.core.bootstrap

import android.content.Context
import com.starringbox.staragent.core.speech.local.LocalSpeechContext
import androidx.activity.result.ActivityResultCaller
import com.starringbox.staragent.core.data.AndroidAppContext
import com.starringbox.staragent.core.data.AppStates
import com.starringbox.staragent.core.toolhost.device.AndroidAccessibilityController
import com.starringbox.staragent.core.platform.installAndroidBrowser
import com.starringbox.staragent.core.platform.media.AndroidMediaPicker
import com.starringbox.staragent.core.platform.media.AndroidMediaSaver
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaSavers
import com.starringbox.staragent.core.platform.notify.AndroidNotifier
import com.starringbox.staragent.core.platform.notify.Notifiers
import com.starringbox.staragent.core.toolhost.permissions.installAndroidPermissions
import com.starringbox.staragent.core.platform.schedule.AndroidTaskScheduler
import com.starringbox.staragent.core.platform.schedule.TaskSchedulers
import com.starringbox.staragent.core.platform.schedule.WakeupTrigger
import com.starringbox.staragent.core.platform.storage.AndroidLocalStore
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.DeviceVoice
import com.starringbox.staragent.core.platform.voice.install
import com.starringbox.staragent.core.platform.voice.installProcess

/**
 * Android 进程级装配：只要 Context 的那部分能力，在 `Application.onCreate` 里调一次。
 *
 * 进程不一定是用户点开的：闹钟（`TaskAlarmReceiver`）、开机广播（`BootReceiver`）、
 * 无障碍服务都能在没有 Activity 的情况下把它拉起来。以前全部装配都挂在
 * `Activity.onCreate` 上，冷启动时存储、调度、唤醒处理器一样都没有，到点的任务静默跳过。
 *
 * 重复调用是空操作（[install] 每次也会先调它）。必须在主线程调用。
 *
 * 顺序有讲究，别调换：`AndroidAppContext` 必须最先装，因为 `core:data` 的
 * `SettingsStore` 要靠它才能落到 SharedPreferences，而 `AppState` 一构造就要读配置；
 * `LocalStores` 也必须在 AppState 构造前装好，它在构造时就决定了要不要落盘。
 */
fun PlatformInstall.installProcess(context: Context) {
    if (processInstalled) return
    processInstalled = true
    val app = context.applicationContext

    // 1. 最先：让 core:data 拿得到 Context
    AndroidAppContext.install(app)
    LocalSpeechContext.install(app)
    installAndroidBrowser(app)
    AndroidAccessibilityController.installContext(app)

    // 2. 本地存储
    LocalStores.install(AndroidLocalStore(app))

    // 3. 播放与系统朗读：不需要 Activity，跟着进程走，避免重建时泄漏 TTS 引擎
    DeviceVoice.installProcess(app)

    // 4. 系统级调度与通知
    TaskSchedulers.install(AndroidTaskScheduler(app))
    Notifiers.install(AndroidNotifier(app))

    // 5. 后台唤醒：冷启动时还没有 AppState，处理器负责按需把它建出来。
    //    AppState 构造时会再注册一次指向自己的处理器，是同一件事。
    WakeupTrigger.registerHandler { AppStates.current().runDueTasksAndNotify() }
}

/**
 * Android Activity 级装配：需要 `registerForActivityResult` 的那部分。
 *
 * **必须在 `Activity.onCreate` 里调**，而且要在 `super.onCreate` 之前——
 * 选图、保存、录音、识别、权限都要 `registerForActivityResult`，它只在 Activity
 * 初始化阶段合法。装晚了会抛 `IllegalStateException`。每次 Activity 重建都要重新装，
 * 因为注册是绑在那个 Activity 实例上的。
 *
 * `context` 传 `applicationContext`：这些实现只拿它读权限、开 `AudioRecord`、
 * 取 `cacheDir`，都不需要 UI 上下文；传 Activity 只会让它们各持一个 Activity 引用。
 * `caller` 则必须是 Activity 本身——注册进去的回调活得比这些对象长。
 */
fun PlatformInstall.install(context: Context, caller: ActivityResultCaller) {
    // 正常路径下 Application 已经装过，这里是空操作；留着是为了不依赖 Manifest 一定配了 Application。
    installProcess(context)

    MediaPickers.install(AndroidMediaPicker(context, caller))
    MediaSavers.install(AndroidMediaSaver(context, caller))
    DeviceVoice.install(context, caller)
    installAndroidPermissions(context, caller)
}

private var processInstalled = false
