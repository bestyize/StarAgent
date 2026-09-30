package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.tools.builtin.CreateTaskTool
import com.starringbox.staragent.core.tools.builtin.RecallMemoryTool
import com.starringbox.staragent.core.tools.builtin.RememberTool
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
}
