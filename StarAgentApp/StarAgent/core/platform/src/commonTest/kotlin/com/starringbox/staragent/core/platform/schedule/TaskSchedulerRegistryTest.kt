package com.starringbox.staragent.core.platform.schedule

import com.starringbox.staragent.core.platform.notify.Notifier
import com.starringbox.staragent.core.platform.notify.Notifiers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskSchedulerRegistryTest {

    @kotlin.test.BeforeTest
    fun setUp() {
        TaskSchedulers.reset()
        Notifiers.reset()
        WakeupTrigger.registerHandler { }
    }

    @AfterTest
    fun tearDown() {
        TaskSchedulers.reset()
        Notifiers.reset()
        WakeupTrigger.registerHandler { }
    }

    @Test
    fun defaultSchedulerIsNoopAndUnavailable() {
        // 初始未安装前，available 为 false，调用不抛异常
        assertFalse(TaskSchedulers.available)
        TaskSchedulers.current.scheduleNextWakeup(1000L)
    }

    @Test
    fun installCustomSchedulerUpdatesCurrentAndAvailable() {
        var scheduledTime: Long? = -1L
        val custom = object : TaskScheduler {
            override fun scheduleNextWakeup(triggerAtMillis: Long?) {
                scheduledTime = triggerAtMillis
            }
        }

        TaskSchedulers.install(custom)
        assertTrue(TaskSchedulers.available)

        TaskSchedulers.current.scheduleNextWakeup(12345678L)
        assertEquals(12345678L, scheduledTime)

        TaskSchedulers.current.scheduleNextWakeup(null)
        assertEquals(null, scheduledTime)
    }

    @Test
    fun wakeupTriggerCallsRegisteredHandler() = runTest {
        var fired = false
        WakeupTrigger.registerHandler {
            fired = true
        }

        WakeupTrigger.fire()
        assertTrue(fired, "WakeupTrigger.fire 应调起注册的 handler")
    }

    @Test
    fun defaultNotifierIsNoop() {
        assertFalse(Notifiers.available)
        Notifiers.current.notify("id-1", "标题", "内容")
    }

    @Test
    fun installCustomNotifierRecordsNotifications() {
        val received = mutableListOf<Triple<String, String, String>>()
        val custom = object : Notifier {
            override fun notify(id: String, title: String, content: String) {
                received += Triple(id, title, content)
            }
        }

        Notifiers.install(custom)
        assertTrue(Notifiers.available)

        Notifiers.current.notify("task-1", "AI新闻", "已完成汇总")
        assertEquals(listOf(Triple("task-1", "AI新闻", "已完成汇总")), received)
    }
}
