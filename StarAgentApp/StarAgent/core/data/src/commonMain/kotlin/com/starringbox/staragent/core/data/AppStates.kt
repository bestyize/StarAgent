package com.starringbox.staragent.core.data

/**
 * 进程级的 [AppState]：一个进程只有一份，活得和进程一样长。
 *
 * 以前它挂在 Composition 上（`remember { AppState() }`），于是：
 * - Activity 重建（旋转、切深色、改语言）就重建一份，重新读盘、重新往全局注册表装 handler；
 * - 闹钟或开机广播冷启动进程时根本没有 Activity，也就没有 AppState，后台任务静默跳过。
 *
 * 首次取用时才构造，因为构造要读盘；只被系统拉起来做别的事（比如绑定无障碍服务）的进程不必付这笔开销。
 *
 * ⚠️ **只在主线程调用。** AppState 里全是 Compose 快照状态，界面也在主线程读写它；
 * 这里不加锁，靠的就是这条约束。后台唤醒入口（`TaskAlarmReceiver` / `BootReceiver`）
 * 因此切到 `Dispatchers.Main` 再触发。
 *
 * 构造前平台能力必须已经装好（至少 `LocalStores` 和 Android 的 `AndroidAppContext`）：
 * AppState 一构造就要读配置、读存档。
 */
object AppStates {
    private var instance: AppState? = null

    fun current(): AppState = instance ?: AppState().also { instance = it }
}
