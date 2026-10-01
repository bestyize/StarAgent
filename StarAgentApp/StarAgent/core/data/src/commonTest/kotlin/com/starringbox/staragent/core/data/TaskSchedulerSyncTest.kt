package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.platform.notify.Notifier
import com.starringbox.staragent.core.platform.notify.Notifiers
import com.starringbox.staragent.core.platform.schedule.TaskScheduler
import com.starringbox.staragent.core.platform.schedule.TaskSchedulers
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskSchedulerSyncTest {

    private var scheduledTime: Long? = -1L
    private val notifications = mutableListOf<Pair<String, String>>()

    @BeforeTest
    fun setUp() {
        LocalStores.install(MemoryStore())
        TaskSchedulers.install(object : TaskScheduler {
            override fun scheduleNextWakeup(triggerAtMillis: Long?) {
                scheduledTime = triggerAtMillis
            }
        })
        Notifiers.install(object : Notifier {
            override fun notify(id: String, title: String, content: String) {
                notifications += (title to content)
            }
        })
    }

    @AfterTest
    fun tearDown() {
        TaskSchedulers.reset()
        Notifiers.reset()
        LocalStores.install(MemoryStore())
    }

    @Test
    fun nextSystemWakeupMillisPicksTheEarliestFutureActiveTask() {
        val state = AppState()
        val now = 1_000_000L
        state.tasks.clear()

        // 任务1：早于当前时间（已到期待跑）
        state.tasks.add(AgentTask("t1", "a", "过去任务", Recurrence.Daily, "08:00", "", nextRunAt = 900_000L, status = TaskStatus.Active))
        // 任务2：未来较近时间
        state.tasks.add(AgentTask("t2", "a", "近未来任务", Recurrence.Daily, "10:00", "", nextRunAt = 1_100_000L, status = TaskStatus.Active))
        // 任务3：未来较远时间
        state.tasks.add(AgentTask("t3", "a", "远未来任务", Recurrence.Daily, "18:00", "", nextRunAt = 1_500_000L, status = TaskStatus.Active))
        // 任务4：暂停任务（不参与调度）
        state.tasks.add(AgentTask("t4", "a", "暂停任务", Recurrence.Daily, "09:00", "", nextRunAt = 1_050_000L, status = TaskStatus.Paused))

        val next = state.nextSystemWakeupMillis(currentMillis = now)
        assertEquals(1_100_000L, next, "应精确挑选未来且处于 Active 状态的最早时刻")
    }

    @Test
    fun nextSystemWakeupMillisReturnsNullWhenNoActiveFutureTasks() {
        val state = AppState()
        state.tasks.clear()
        assertNull(state.nextSystemWakeupMillis(currentMillis = 1_000_000L))

        // 只有 Done 的任务
        state.tasks.add(AgentTask("t1", "a", "已完成", Recurrence.Once, "08:00", "", nextRunAt = 2_000_000L, status = TaskStatus.Done))
        assertNull(state.nextSystemWakeupMillis(currentMillis = 1_000_000L))
    }

    @Test
    fun syncNextSystemWakeupPushesTimeToRegisteredScheduler() {
        val state = AppState()
        state.tasks.clear()
        state.tasks.add(AgentTask("t1", "a", "未来任务", Recurrence.Daily, "10:00", "", nextRunAt = 1_200_000L, status = TaskStatus.Active))

        state.syncNextSystemWakeup(currentMillis = 1_000_000L)
        assertEquals(1_200_000L, scheduledTime)
    }

    @Test
    fun taskExecutionSuccessSendsNotification() = runTest {
        val state = AppState(
            stream = { _, _ ->
                kotlinx.coroutines.flow.flow {
                    emit(com.starringbox.staragent.core.llm.ChatStreamEvent.Delta("今日科技新闻要点：1. AI芯片突破；2. 新模型发布。"))
                    emit(com.starringbox.staragent.core.llm.ChatStreamEvent.Completed)
                }
            }
        )
        state.updateProvider(
            state.providerById("deepseek")!!.copy(apiKey = "test-key", models = listOf("test-model")),
        )
        state.tasks.clear()
        val dueTask = AgentTask("t-due", "a-xing", "科技早报", Recurrence.Daily, "09:00", "", nextRunAt = 500L, status = TaskStatus.Active)
        state.tasks.add(dueTask)

        state.runDueTasksAndNotify()

        assertEquals(1, notifications.size, "任务执行成功后应发出一条系统通知")
        val (title, content) = notifications.single()
        assertTrue(title.contains("科技早报"))
        assertTrue(content.contains("今日科技新闻要点"))
    }
}
