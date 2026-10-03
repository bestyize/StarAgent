package com.starringbox.staragent.core.platform.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val pendingResult = goAsync()
        // 主线程：处理器会读写 AppState 的快照状态（冷启动时还要构造它），和界面在同一个线程上。
        // 任务里的网络调用都是挂起的，不会卡住主线程。
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                WakeupTrigger.fire()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
