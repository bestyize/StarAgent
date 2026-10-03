package com.starringbox.staragent

import android.app.Application
import com.starringbox.staragent.core.bootstrap.PlatformInstall
import com.starringbox.staragent.core.bootstrap.installProcess

/**
 * 进程入口。闹钟、开机广播、无障碍服务都可能在没有 Activity 的情况下拉起进程，
 * 只要 Context 的平台能力必须在这里装好，后台唤醒才有东西可用。
 * 需要 Activity 的那部分仍由 [MainActivity] 装。
 */
class StarAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatformInstall.installProcess(this)
    }
}
