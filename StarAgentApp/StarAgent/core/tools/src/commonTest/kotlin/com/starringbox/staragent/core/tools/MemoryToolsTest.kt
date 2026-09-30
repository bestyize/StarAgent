package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
import com.starringbox.staragent.core.tools.builtin.CancelTaskTool
import com.starringbox.staragent.core.tools.builtin.CreateTaskTool
import com.starringbox.staragent.core.tools.builtin.ListTasksTool
import com.starringbox.staragent.core.tools.builtin.RecallMemoryTool
import com.starringbox.staragent.core.tools.builtin.RememberTool
import com.starringbox.staragent.core.tools.builtin.UpdateTaskTool
import com.starringbox.staragent.core.tools.ports.TaskSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

class MemoryToolsTest {

    @Test
    fun rememberWritesTheCategoryAndTextItWasGiven() = runTest {
        val memory = FakeMemoryPort()
        val result = RememberTool.execute(
            args("{\"category\":\"Preference\",\"text\":\"爱吃辣\"}"),
            testHost(memory = memory),
        )

        assertEquals(listOf(MemoryCategory.Preference to "爱吃辣"), memory.written)
        assertFalse(result.isError)
    }

    @Test
    fun rememberRejectsUnknownCategoryInsteadOfInventingOne() = runTest {
        // 枚举之外的分类不该悄悄建出一个新分类——那是数据污染
        val memory = FakeMemoryPort()
        val result = RememberTool.execute(
            args("{\"category\":\"Favourite\",\"text\":\"爱吃辣\"}"),
            testHost(memory = memory),
        )

        assertTrue(result.isError)
        assertEquals(emptyList(), memory.written)
        assertTrue(result.content.contains("Preference"), "错误里要列出合法取值")
    }

    @Test
    fun rememberRejectsBlankText() = runTest {
        val memory = FakeMemoryPort()
        val result = RememberTool.execute(
            args("{\"category\":\"Preference\",\"text\":\"   \"}"),
            testHost(memory = memory),
        )
        assertTrue(result.isError)
        assertEquals(emptyList(), memory.written)
    }

    @Test
    fun rememberRejectsMissingField() = runTest {
        val result = RememberTool.execute(args("{\"category\":\"Preference\"}"), testHost())
        assertTrue(result.isError)
    }

    @Test
    fun rememberRejectsMissingCategory() = runTest {
        // 缺 category 走的是另一条分支（字段整个没有，不是值不认识），两处都得拦住
        val memory = FakeMemoryPort()
        val result = RememberTool.execute(args("{\"text\":\"爱吃辣\"}"), testHost(memory = memory))

        assertTrue(result.isError)
        assertEquals(emptyList(), memory.written)
    }

    @Test
    fun recallPassesQueryThroughAndFormatsHits() = runTest {
        val memory = FakeMemoryPort(
            listOf(MemoryItem("m1", "a-xing", MemoryCategory.Preference, "爱吃辣"))
        )
        val result = RecallMemoryTool.execute(args("{\"query\":\"口味\"}"), testHost(memory = memory))

        assertEquals(listOf("口味"), memory.recalledQueries)
        assertTrue(result.content.contains("爱吃辣"))
    }

    @Test
    fun recallSaysSoWhenNothingFound() = runTest {
        // 空结果要让模型知道"是没有"，不是"工具坏了"——否则它会反复重试
        val result = RecallMemoryTool.execute(args("{\"query\":\"不存在\"}"), testHost())
        assertFalse(result.isError)
        assertTrue(result.content.contains("没有"))
    }
}

class TaskToolsTest {

    @Test
    fun createMapsArgumentsOntoThePort() = runTest {
        val tasks = FakeTaskPort()
        val result = CreateTaskTool.execute(
            args("{\"title\":\"看新闻\",\"recurrence\":\"Daily\",\"schedule\":\"每天 09:00\"}"),
            testHost(tasks = tasks),
        )

        // 每个字段都要查：参数互换或硬编码成 Daily 都会让只查 title 的断言照样过
        assertEquals(
            listOf(CreateCall("a-xing", "看新闻", Recurrence.Daily, "每天 09:00", null)),
            tasks.createCalls,
        )
        assertTrue(result.content.contains("task-1"), "要把 id 还给模型，它才能接着引用")
    }

    @Test
    fun createRejectsUnknownRecurrence() = runTest {
        val tasks = FakeTaskPort()
        val result = CreateTaskTool.execute(
            args("{\"title\":\"看新闻\",\"recurrence\":\"Hourly\",\"schedule\":\"每小时\"}"),
            testHost(tasks = tasks),
        )
        assertTrue(result.isError)
        assertEquals(emptyList(), tasks.createCalls)
    }

    @Test
    fun createRejectsBlankTitle() = runTest {
        val result = CreateTaskTool.execute(
            args("{\"title\":\"  \",\"recurrence\":\"Daily\",\"schedule\":\"每天 09:00\"}"),
            testHost(),
        )
        assertTrue(result.isError)
    }

    @Test
    fun createAcceptsOptionalNote() = runTest {
        val tasks = FakeTaskPort()
        val result = CreateTaskTool.execute(
            args("{\"title\":\"看新闻\",\"recurrence\":\"Daily\",\"schedule\":\"每天 09:00\"," +
                "\"note\":\"只看 AI 方向\"}"),
            testHost(tasks = tasks),
        )
        assertFalse(result.isError)
        assertEquals("只看 AI 方向", tasks.createCalls.single().note)
    }

    @Test
    fun createMapsBlankNoteToNull() = runTest {
        // 空白 note 要落成 null 而不是空串——否则任务详情页会显示一片空白说明
        val tasks = FakeTaskPort()
        val result = CreateTaskTool.execute(
            args("{\"title\":\"看新闻\",\"recurrence\":\"Weekly\",\"schedule\":\"每周一 09:00\"," +
                "\"note\":\"   \"}"),
            testHost(tasks = tasks),
        )

        assertFalse(result.isError)
        assertEquals(
            listOf(CreateCall("a-xing", "看新闻", Recurrence.Weekly, "每周一 09:00", null)),
            tasks.createCalls,
        )
    }

    @Test
    fun listTellsTheModelThereAreNoTasksRatherThanReturningEmpty() = runTest {
        // 空结果要说成一句话。返回空字符串的话模型会以为工具坏了、反复重试。
        val result = ListTasksTool.execute(args("{}"), testHost())
        assertTrue(result.content.contains("没有"))
        assertFalse(result.isError)
    }

    @Test
    fun listFormatsTasksWithTheirIds() = runTest {
        // id 必须在里面，否则模型没法接着 update 或 cancel
        val tasks = FakeTaskPort().apply {
            listed = listOf(
                TaskSummary(
                    id = "task-7",
                    title = "看新闻",
                    recurrence = Recurrence.Daily,
                    scheduleLabel = "每天 09:00",
                    nextRunLabel = "明天 09:00",
                    status = TaskStatus.Active,
                )
            )
        }
        val result = ListTasksTool.execute(args("{}"), testHost(tasks = tasks))

        // agentId 的检查在端口实现里，工具唯一的职责就是把它原样传下去
        assertEquals(listOf("a-xing"), tasks.listedAgentIds)
        assertTrue(result.content.contains("task-7"))
        assertTrue(result.content.contains("看新闻"))
        assertTrue(result.content.contains("明天 09:00"))
    }

    @Test
    fun updatePassesBothFieldsThrough() = runTest {
        val tasks = FakeTaskPort()
        val result = UpdateTaskTool.execute(
            args("{\"id\":\"task-1\",\"title\":\"新名\",\"schedule\":\"每天 21:00\"}"),
            testHost(tasks = tasks),
        )

        // 两个字段都查：只查 isError 的话，title 和 schedule 互换照样过
        assertEquals(
            listOf(UpdateCall("a-xing", "task-1", "新名", "每天 21:00")),
            tasks.updateCalls,
        )
        assertFalse(result.isError)
    }

    @Test
    fun updateAllowsChangingOnlyTheSchedule() = runTest {
        // 改时间是最常见的用法，不该强制带 title
        val tasks = FakeTaskPort()
        val result = UpdateTaskTool.execute(
            args("{\"id\":\"task-1\",\"schedule\":\"每天 21:00\"}"),
            testHost(tasks = tasks),
        )

        // 没传的 title 必须落成 null（"保持原样"），不能填成空串
        assertEquals(
            listOf(UpdateCall("a-xing", "task-1", null, "每天 21:00")),
            tasks.updateCalls,
        )
        assertFalse(result.isError)
    }

    @Test
    fun updateRequiresAtLeastOneFieldToChange() = runTest {
        val tasks = FakeTaskPort()
        val result = UpdateTaskTool.execute(args("{\"id\":\"task-1\"}"), testHost(tasks = tasks))

        assertTrue(result.isError)
        assertTrue(result.content.contains("改什么"))
        // 一个字段都没给时不该真的调端口——端口那边无从表达"什么都不改"
        assertEquals(emptyList(), tasks.updateCalls)
    }

    @Test
    fun updateReportsWhenTheTaskIsGone() = runTest {
        // 端口返回 false：任务不存在、或者不属于这个智能体。两种都由实现判断。
        val tasks = FakeTaskPort().apply { updateResult = false }
        val result = UpdateTaskTool.execute(
            args("{\"id\":\"task-9\",\"title\":\"x\"}"),
            testHost(tasks = tasks),
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("task-9"))
    }

    @Test
    fun cancelReportsSuccess() = runTest {
        val tasks = FakeTaskPort()
        val result = CancelTaskTool.execute(args("{\"id\":\"task-1\"}"), testHost(tasks = tasks))

        assertEquals(listOf(CancelCall("a-xing", "task-1")), tasks.cancelCalls)
        assertFalse(result.isError)
    }

    @Test
    fun cancelReportsWhenTheTaskIsGone() = runTest {
        // 和 update 同一条口径：端口说没找到，就把 id 回给模型让它去 list_tasks
        val tasks = FakeTaskPort().apply { cancelResult = false }
        val result = CancelTaskTool.execute(args("{\"id\":\"task-9\"}"), testHost(tasks = tasks))

        assertTrue(result.isError)
        assertTrue(result.content.contains("task-9"))
    }

    @Test
    fun cancelRequiresId() = runTest {
        val tasks = FakeTaskPort()
        assertTrue(CancelTaskTool.execute(args("{}"), testHost(tasks = tasks)).isError)
        // 缺 id 时不该拿着空串去调端口
        assertEquals(emptyList(), tasks.cancelCalls)
    }
}
