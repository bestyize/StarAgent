package com.starringbox.staragent.core.platform.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class AndroidTaskScheduler(private val context: Context) : TaskScheduler {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override val status: TaskSchedulerStatus
        get() = when {
            alarmManager == null -> TaskSchedulerStatus.Unavailable
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() ->
                TaskSchedulerStatus.PermissionRequired
            else -> TaskSchedulerStatus.Available
        }

    override fun scheduleNextWakeup(triggerAtMillis: Long?) {
        if (alarmManager == null) return
        if (triggerAtMillis != null && status == TaskSchedulerStatus.PermissionRequired) return

        val intent = Intent(context, TaskAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (triggerAtMillis == null) {
            alarmManager.cancel(pendingIntent)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (_: SecurityException) {
            // 用户尚未授予精确闹钟特殊访问权限；由上层展示引导，不让创建任务崩溃。
        }
    }

    companion object {
        const val ALARM_REQUEST_CODE = 10086
    }
}
