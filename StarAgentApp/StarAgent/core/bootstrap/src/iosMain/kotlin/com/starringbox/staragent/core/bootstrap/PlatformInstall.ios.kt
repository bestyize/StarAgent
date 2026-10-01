package com.starringbox.staragent.core.bootstrap

import com.starringbox.staragent.core.platform.notify.IosNotifier
import com.starringbox.staragent.core.platform.notify.Notifiers
import com.starringbox.staragent.core.platform.schedule.IosTaskScheduler
import com.starringbox.staragent.core.platform.schedule.TaskSchedulers
import com.starringbox.staragent.core.platform.voice.DeviceVoice
import com.starringbox.staragent.core.platform.voice.install

/**
 * iOS：把这一端的能力一次装齐。
 */
fun PlatformInstall.install() {
    // 调度与通知
    TaskSchedulers.install(IosTaskScheduler())
    Notifiers.install(IosNotifier())

    DeviceVoice.install()
}
