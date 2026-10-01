package com.starringbox.staragent.core.platform.schedule

import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS 端任务调度器：基于 BackgroundTasks 框架注册后台刷新请求。
 */
@OptIn(ExperimentalForeignApi::class)
class IosTaskScheduler : TaskScheduler {

    override fun scheduleNextWakeup(triggerAtMillis: Long?) {
        if (triggerAtMillis == null) {
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_ID)
            return
        }

        val seconds = triggerAtMillis / 1000.0
        val request = BGAppRefreshTaskRequest(TASK_ID).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(seconds)
        }

        runCatching {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
        }
    }

    companion object {
        const val TASK_ID = "com.starringbox.staragent.task.refresh"
    }
}
