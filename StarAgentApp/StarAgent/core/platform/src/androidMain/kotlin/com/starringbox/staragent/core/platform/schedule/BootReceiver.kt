package com.starringbox.staragent.core.platform.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 开机广播：设备重启后自动拉起检查并恢复系统闹钟预约。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        // 主线程，理由同 TaskAlarmReceiver。
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                WakeupTrigger.fire()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
