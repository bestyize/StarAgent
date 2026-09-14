package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.defaultScheduleFor
import com.starringbox.staragent.core.model.nextRunLabelFor
import com.starringbox.staragent.core.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 任务的编辑。
 *
 * 之前手动新建的任务只能是"一次性 / 今天 10:00"——类型和时间在详情页是只读的，
 * 而且"下次执行"是创建时算一次就不再变，改了类型也会一直显示旧时刻。
 */
class TaskSaveTest {

    @AfterTest
    fun resetStores() {
        LocalStores.install(MemoryStore())
    }

    private fun state(): AppState {
        LocalStores.install(MemoryStore())
        return AppState()
    }

    @Test
    fun nextRunLabelFollowsTheTypeAndTheTime() {
        assertEquals("明天 07:30", nextRunLabelFor(Recurrence.Daily, "每天 07:30"))
        assertEquals("下周一 07:30", nextRunLabelFor(Recurrence.Weekly, "每周一 07:30"))
        assertEquals("下月 1 日 07:30", nextRunLabelFor(Recurrence.Monthly, "每月 1 日 07:30"))
        assertEquals("待触发", nextRunLabelFor(Recurrence.Once, "今天 10:00"))
        assertEquals("满足条件时触发", nextRunLabelFor(Recurrence.Conditional, "当连接 Wi-Fi 时"))
    }

    @Test
    fun timeIsPickedOutOfTheDescription() {
        // 用户自己敲的时间描述也得认出来，而不是一律按 09:00
        assertEquals("明天 22:05", nextRunLabelFor(Recurrence.Daily, "每天晚上 22:05 提醒我"))
        // 认不出时刻就退回 09:00，而不是崩掉或者显示空
        assertEquals("明天 09:00", nextRunLabelFor(Recurrence.Daily, "随便什么时候"))
    }

    @Test
    fun everyTypeHasADefaultDescription() {
        // 新建时输入框靠它预填，不然用户要自己拼"每天 09:00"这种格式
        Recurrence.entries.forEach { entry ->
            assertTrue(defaultScheduleFor(entry).isNotBlank(), "$entry 缺一个默认的时间描述")
        }
    }

    @Test
    fun newTaskStaysOutOfTheListUntilSaved() {
        val state = state()
        val before = state.tasks.size

        val draft = state.newTask(state.agents.first().id)

        assertEquals(before, state.tasks.size, "新建的任务不该悄悄进列表——用户返回就留下垃圾了")
        assertNull(state.task(draft.id))
    }

    @Test
    fun savingANewTaskAddsItAndComputesTheNextRun() {
        val state = state()
        val before = state.tasks.size
        val draft = state.newTask(state.agents.first().id)

        state.saveTask(
            draft.copy(title = "汇总 AI 新闻", recurrence = Recurrence.Daily, scheduleLabel = "每天 07:30")
        )

        assertEquals(before + 1, state.tasks.size)
        val saved = state.task(draft.id)!!
        assertEquals("明天 07:30", saved.nextRunLabel, "\"下次执行\"必须在保存时算出来")
    }

    @Test
    fun changingTheTypeRecomputesTheNextRun() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)
        state.saveTask(
            draft.copy(title = "汇总", recurrence = Recurrence.Daily, scheduleLabel = "每天 07:30")
        )
        val daily = state.task(draft.id)!!

        // 把"每天"改成"每月"——时刻不变，但"下次"必须跟着变
        state.saveTask(daily.copy(recurrence = Recurrence.Monthly))

        assertEquals(
            "下月 1 日 07:30",
            state.task(draft.id)!!.nextRunLabel,
            "改了类型，「下次执行」还停在旧值的话，卡片上的时间就是错的",
        )
    }

    @Test
    fun changingTheTimeRecomputesTheNextRunToo() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)
        state.saveTask(
            draft.copy(title = "汇总", recurrence = Recurrence.Daily, scheduleLabel = "每天 07:30")
        )

        state.saveTask(state.task(draft.id)!!.copy(scheduleLabel = "每天 21:00"))

        assertEquals("明天 21:00", state.task(draft.id)!!.nextRunLabel)
    }

    @Test
    fun theTaskCanBeHandedToAnotherAgent() {
        val state = state()
        val first = state.agents[0].id
        val second = state.agents[1].id
        val draft = state.newTask(first)

        state.saveTask(draft.copy(title = "换个人做", agentId = second))

        assertEquals(second, state.task(draft.id)!!.agentId)
        assertEquals(1, state.tasksOf(second).count { it.id == draft.id })
    }
}
