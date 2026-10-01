package com.starringbox.staragent.core.platform.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopTaskSchedulerTest {

    private val scheduler = DesktopTaskScheduler()

    @AfterTest
    fun tearDown() {
        scheduler.scheduleNextWakeup(null)
    }

    @Test
    fun scheduleTriggersWakeupAfterDelay() = runBlocking {
        val triggered = CompletableDeferred<Unit>()
        WakeupTrigger.registerHandler {
            triggered.complete(Unit)
        }

        // 预约 50 毫秒后触发
        val triggerTime = System.currentTimeMillis() + 50L
        scheduler.scheduleNextWakeup(triggerTime)

        val result = withTimeoutOrNull(500L) {
            triggered.await()
        }
        assertNotNull(result, "到点后应成功唤醒 WakeupTrigger")
    }

    @Test
    fun scheduleWithNullCancelsPendingWakeup() = runBlocking {
        val triggered = CompletableDeferred<Unit>()
        WakeupTrigger.registerHandler {
            triggered.complete(Unit)
        }

        val triggerTime = System.currentTimeMillis() + 100L
        scheduler.scheduleNextWakeup(triggerTime)
        // 立即取消
        scheduler.scheduleNextWakeup(null)

        val result = withTimeoutOrNull(250L) {
            triggered.await()
        }
        assertNull(result, "取消后不应触发 WakeupTrigger")
    }
}
