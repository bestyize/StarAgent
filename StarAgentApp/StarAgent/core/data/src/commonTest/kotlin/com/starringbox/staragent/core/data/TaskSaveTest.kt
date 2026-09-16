package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.defaultScheduleFor
import com.starringbox.staragent.core.platform.storage.LocalStores
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 任务的编辑与排期。
 *
 * 之前手动新建的任务只能是"一次性 / 今天 10:00"——类型和时间在详情页是只读的；
 * 而且"下次执行"是创建时算一次就不再变，改了类型也会一直显示旧时刻。
 *
 * 这里断言的是**排期被重算**，不写死具体的时刻文案——那会依赖跑测试时是几点，早晚会飘。
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
    fun savingANewTaskAddsItAndSchedulesIt() {
        val state = state()
        val before = state.tasks.size
        val draft = state.newTask(state.agents.first().id)

        state.saveTask(
            draft.copy(title = "汇总 AI 新闻", recurrence = Recurrence.Daily, scheduleLabel = "每天 07:30")
        )

        assertEquals(before + 1, state.tasks.size)
        val saved = state.task(draft.id)!!
        assertTrue((saved.nextRunAt ?: 0L) > 0L, "保存时要算出真正的执行时刻，否则它永远不会跑")
        assertTrue(saved.nextRunLabel.isNotBlank())
    }

    /**
     * 这条守的是那半个 bug：以前 `updateTask` 只是原样存回去，
     * 用户改了类型或时间，"下次执行"还停在旧值上。
     */
    @Test
    fun savingRecomputesTheScheduleInsteadOfKeepingTheStoredOne() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)

        state.saveTask(
            draft.copy(
                title = "汇总",
                recurrence = Recurrence.Daily,
                scheduleLabel = "每天 09:00",
                nextRunAt = 1L, // 故意塞一个早就过去的时刻
                nextRunLabel = "早就过去了",
            )
        )

        val saved = state.task(draft.id)!!
        assertTrue((saved.nextRunAt ?: 0L) > 1L, "保存时必须重算，不能沿用传进来的旧时刻")
        assertNotEquals("早就过去了", saved.nextRunLabel, "文案也要跟着重算")
    }

    /**
     * 换成"条件触发"来断言，而不是换成"每月"。
     *
     * 因为"每天"和"每月 15 日"的下一次**可能正好是同一天同一时刻**（今天就是 14 号时），
     * 拿两个时刻比大小会写成一条看日期飘的测试。条件触发没有时刻，判定是确定的。
     */
    @Test
    fun changingTheTypeReschedules() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)
        state.saveTask(
            draft.copy(title = "汇总", recurrence = Recurrence.Daily, scheduleLabel = "每天 09:00")
        )
        assertTrue((state.task(draft.id)!!.nextRunAt ?: 0L) > 0L, "定时任务该有排期")

        state.saveTask(
            state.task(draft.id)!!.copy(
                recurrence = Recurrence.Conditional,
                scheduleLabel = "当连接 Wi-Fi 时",
            )
        )

        assertNull(state.task(draft.id)!!.nextRunAt, "换成条件触发后就不该再有固定时刻")
    }

    @Test
    fun changingTheTimeReschedules() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)
        state.saveTask(
            draft.copy(title = "汇总", recurrence = Recurrence.Daily, scheduleLabel = "每天 07:00")
        )
        val morning = state.task(draft.id)!!

        state.saveTask(morning.copy(scheduleLabel = "每天 21:00"))

        assertNotEquals(morning.nextRunAt, state.task(draft.id)!!.nextRunAt, "换了时间，排期必须跟着换")
    }

    @Test
    fun conditionalTasksHaveNoMomentToSchedule() {
        val state = state()
        val draft = state.newTask(state.agents.first().id)

        state.saveTask(
            draft.copy(
                title = "连上 Wi-Fi 时同步",
                recurrence = Recurrence.Conditional,
                scheduleLabel = "当连接 Wi-Fi 时",
            )
        )

        assertNull(state.task(draft.id)!!.nextRunAt, "条件触发没有固定时刻，不该被排期")
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
