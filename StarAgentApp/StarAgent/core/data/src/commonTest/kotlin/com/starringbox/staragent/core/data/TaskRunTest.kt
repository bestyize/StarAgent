package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.storage.LocalStores
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 定时执行。
 *
 * 以前任务是"只会显示、不会跑"的——没有任何读取当前时间的代码。现在到点会真的让它的智能体
 * 做一遍，结果落回那条对话。
 *
 * 时间在这里不是注入的：测试直接把任务的 [AgentTask.nextRunAt] 推到过去来模拟"到点了"，
 * 这样既不用给时钟开口子，也不会因为跑测试的时刻不同而飘。
 */
class TaskRunTest {

    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    /** 造一个"能连上模型"的状态：给第一家服务商配上假 Key。 */
    private fun readyState(reply: String = "本期新闻汇总好了。"): AppState {
        val state = AppState { _, _ ->
            flow {
                emit(ChatStreamEvent.Delta(reply))
                emit(ChatStreamEvent.Completed)
            }
        }
        val index = state.providers.indexOfFirst { it.id == state.providers.first().id }
        state.providers[index] = state.providers[index].copy(
            apiKey = "test-key",
            models = listOf("test-model"),
        )
        return state
    }

    /** 把任务的排期推到过去，等价于"时间到了"。 */
    private fun makeDue(state: AppState, taskId: String) {
        val index = state.tasks.indexOfFirst { it.id == taskId }
        state.tasks[index] = state.tasks[index].copy(status = TaskStatus.Active, nextRunAt = 1L)
    }

    @Test
    fun aDueTaskRunsAndItsResultLandsInTheConversation() = runTest {
        val state = readyState()
        val task = state.tasks.first()
        makeDue(state, task.id)

        state.runDueTasks()

        val conversation = state.messagesOf(task.agentId)
        assertTrue(
            conversation.any { (it.body as? MessageBody.Text)?.text == "本期新闻汇总好了。" },
            "任务的结果要落回它所属智能体的对话里，否则用户根本不知道它跑过",
        )
        assertTrue(
            conversation.any { (it.body as? MessageBody.Notice)?.text?.contains(task.title) == true },
            "对话里要能看出这条是任务触发的，而不是智能体突然自己开口",
        )
    }

    @Test
    fun afterRunningTheNextOccurrenceIsScheduled() = runTest {
        val state = readyState()
        val task = state.tasks.first()
        makeDue(state, task.id)

        state.runDueTasks()

        val after = state.task(task.id)!!
        assertTrue((after.nextRunAt ?: 0L) > 1L, "跑完必须把期排到将来，否则每半分钟又跑一次")
        assertTrue(after.lastRunLabel!!.contains("已完成"))
    }

    @Test
    fun aOneOffTaskIsDoneAfterItRuns() = runTest {
        val state = readyState()
        val task = state.newTask(state.agents.first().id)
        state.saveTask(
            task.copy(title = "跑一次就好", recurrence = Recurrence.Once, scheduleLabel = "今天 10:00")
        )
        makeDue(state, task.id)

        state.runDueTasks()

        val after = state.task(task.id)!!
        assertEquals(TaskStatus.Done, after.status)
        assertNull(after.nextRunAt, "一次性任务跑完就不该再排期")
    }

    @Test
    fun aTaskThatIsNotDueIsLeftAlone() = runTest {
        var calls = 0
        val state = AppState { _, _ ->
            calls++
            flow { emit(ChatStreamEvent.Completed) }
        }

        state.runDueTasks()

        assertEquals(0, calls, "还没到点的任务不该被动到")
    }

    /**
     * 第一次打开带调度的版本时，旧存档里的任务都没有排期。
     *
     * 这时只该给它们补上"下一次"，**不该**把过去错过的那些一次性补跑——
     * 一打开就刷出一串执行，比不执行更吓人。
     */
    @Test
    fun upgradingAnOldSaveSchedulesWithoutRunning() = runTest {
        var calls = 0
        val state = AppState { _, _ ->
            calls++
            flow { emit(ChatStreamEvent.Completed) }
        }
        // 模拟旧存档：所有任务都没有执行时刻
        state.tasks.indices.forEach { index ->
            state.tasks[index] = state.tasks[index].copy(nextRunAt = null)
        }

        state.runDueTasks()

        assertEquals(0, calls, "补排期不该顺手把任务跑了")
        assertTrue(
            state.tasks.all { it.nextRunAt != null || it.recurrence == Recurrence.Conditional },
            "定时类的任务都该拿到下一次的执行时刻",
        )
    }

    @Test
    fun aTaskWithNoModelIsRescheduledRatherThanRetriedEveryTick() = runTest {
        val state = AppState()
        // 不能假设"服务商没配 Key"：AppState 会把开发机上真实的偏好设置读回来，
        // 那样这条测试会真的发一次网络请求。显式清掉。
        state.providers.indices.forEach { index ->
            state.providers[index] = state.providers[index].copy(apiKey = "")
        }
        val task = state.newTask(state.agents.first().id)
        state.saveTask(
            task.copy(title = "跑一下", recurrence = Recurrence.Daily, scheduleLabel = "每天 09:00")
        )
        makeDue(state, task.id)

        state.runDueTasks()

        val after = state.task(task.id)!!
        assertTrue(
            (after.nextRunAt ?: 0L) > 1L,
            "没配模型也要把期排下去，否则它会一直停在到点、每半分钟重试一次",
        )
        assertTrue(after.lastRunLabel!!.contains("没有可用的模型"))
    }
}
