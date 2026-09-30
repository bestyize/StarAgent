package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.data.ports.AppTaskPort
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskPortTest {

    /**
     * LocalStores 是全局单例，而端口每次写都会落盘。
     *
     * 本类留下的任务会进到**下一个**构造 AppState 的测试里（比如 TaskRunTest 的
     * "补排期不该顺手跑任务"——那条断言所有非条件任务都有下次执行时刻，而这里
     * cancel 留下的是一条 Done，补排期会正确地跳过它）。跑完清干净，与同模块的
     * TaskSaveTest / TaskRunTest 保持一致。
     */
    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    private fun state() = AppState()

    @Test
    fun createAddsTaskForTheRightAgent() = runTest {
        val state = state()
        val agentId = state.agents.first().id
        val port = AppTaskPort(state)

        val id = port.create(agentId, "看 AI 新闻", Recurrence.Daily, "每天 09:00", null)

        val created = state.tasks.firstOrNull { it.id == id }
        assertNotNull(created)
        assertEquals(agentId, created.agentId)
        assertEquals("看 AI 新闻", created.title)
        // 排期要算出来，否则调度器永远不跑它
        assertNotNull(created.nextRunAt)
    }

    @Test
    fun createFillsNextRunLabelNotJustTheInstant() = runTest {
        // 界面显示的是 nextRunLabel；只算 nextRunAt 会让卡片写着"未排期"
        val state = state()
        val agentId = state.agents.first().id
        val id = AppTaskPort(state).create(agentId, "看新闻", Recurrence.Daily, "每天 09:00", null)
        val created = state.tasks.first { it.id == id }
        assertTrue(created.nextRunLabel.isNotBlank())
    }

    @Test
    fun listOnlyReturnsThatAgentsTasks() = runTest {
        val state = state()
        val (first, second) = state.agents.take(2).map { it.id }
        val port = AppTaskPort(state)
        port.create(second, "别人的", Recurrence.Daily, "每天 09:00", null)

        val listed = port.list(first)
        assertTrue(listed.none { it.title == "别人的" })
    }

    @Test
    fun cancelMarksDoneRatherThanDeleting() = runTest {
        // 删掉的话用户再也看不到它出过什么，任务详情页的历史就断了
        val state = state()
        val agentId = state.agents.first().id
        val port = AppTaskPort(state)
        val id = port.create(agentId, "一件事", Recurrence.Daily, "每天 09:00", null)

        assertTrue(port.cancel(agentId, id))
        val after = state.tasks.first { it.id == id }
        assertEquals(TaskStatus.Done, after.status)
    }

    @Test
    fun updateChangesTitleAndReschedules() = runTest {
        val state = state()
        val agentId = state.agents.first().id
        val port = AppTaskPort(state)
        val id = port.create(agentId, "旧名", Recurrence.Daily, "每天 09:00", null)

        assertTrue(port.update(agentId, id, title = "新名", scheduleLabel = "每天 21:00"))
        val after = state.tasks.first { it.id == id }
        assertEquals("新名", after.title)
        assertEquals("每天 21:00", after.scheduleLabel)
        assertNotNull(after.nextRunAt)
    }

    @Test
    fun updateReturnsFalseForUnknownTask() = runTest {
        assertFalse(AppTaskPort(state()).update("a-xing", "不存在", title = "x", scheduleLabel = null))
    }

    @Test
    fun updateRefusesAnotherAgentsTask() = runTest {
        // 模型不该能通过工具改到别的智能体的任务
        val state = state()
        val (first, second) = state.agents.take(2).map { it.id }
        val port = AppTaskPort(state)
        val id = port.create(second, "别人的", Recurrence.Daily, "每天 09:00", null)

        assertFalse(port.update(first, id, title = "改掉", scheduleLabel = null))
        assertEquals("别人的", state.tasks.first { it.id == id }.title)
    }
}
