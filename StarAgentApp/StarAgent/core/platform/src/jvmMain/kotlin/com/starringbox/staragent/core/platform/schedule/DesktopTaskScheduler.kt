package com.starringbox.staragent.core.platform.schedule

import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 桌面端任务调度器：基于单线程守护线程池进行精确延迟唤醒。
 * 守护线程（Daemon Thread）保证不会阻止 JVM 进程正常退出。
 */
class DesktopTaskScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "staragent-desktop-scheduler"
        }
    }
) : TaskScheduler {

    private var currentFuture: ScheduledFuture<*>? = null

    @Synchronized
    override fun scheduleNextWakeup(triggerAtMillis: Long?) {
        currentFuture?.cancel(false)
        currentFuture = null

        if (triggerAtMillis == null) return

        val now = System.currentTimeMillis()
        val delay = maxOf(0L, triggerAtMillis - now)

        currentFuture = executor.schedule({
            runBlocking {
                WakeupTrigger.fire()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
}
