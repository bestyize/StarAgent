package com.starringbox.staragent.core.platform.schedule

/**
 * 跨平台系统级任务调度器。
 */
interface TaskScheduler {
    /**
     * 向操作系统预约下一次系统唤醒时刻（epoch 毫秒）。
     * [triggerAtMillis] 为 null 时表示当前没有待执行的活跃任务，清除操作系统的唤醒预约。
     */
    fun scheduleNextWakeup(triggerAtMillis: Long?)

    object Noop : TaskScheduler {
        override fun scheduleNextWakeup(triggerAtMillis: Long?) = Unit
    }
}

object TaskSchedulers {
    private var installed: TaskScheduler = TaskScheduler.Noop

    fun install(scheduler: TaskScheduler) {
        installed = scheduler
    }

    fun reset() {
        installed = TaskScheduler.Noop
    }

    val current: TaskScheduler get() = installed
    val available: Boolean get() = installed !== TaskScheduler.Noop
}
