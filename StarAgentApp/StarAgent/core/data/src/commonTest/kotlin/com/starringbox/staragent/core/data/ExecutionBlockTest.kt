package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionBlockTest {

    @Test
    fun turnsCallsIntoReadableSteps() {
        // 步骤上给用户看的是"在做什么"，不是工具的内部名字
        val steps = executionStepsFor(
            calls = listOf(ToolCall("c1", "web_fetch", "{\"url\":\"https://example.com\"}")),
            results = emptyList(),
        )
        assertEquals(1, steps.size)
        assertTrue(steps[0].label.isNotBlank())
        assertTrue(!steps[0].label.contains("web_fetch"), "别把内部工具名摊给用户看")
    }

    @Test
    fun marksFinishedStepsAsDone() {
        val steps = executionStepsFor(
            calls = listOf(ToolCall("c1", "remember", "{}")),
            results = listOf(ToolResult(callId = "c1", content = "已记住")),
        )
        assertTrue(steps.all { it.done || steps.size > 1 })
    }

    @Test
    fun unknownToolStillGetsALabel() {
        // 没见过的工具也得有个说法，不能空白
        val steps = executionStepsFor(
            calls = listOf(ToolCall("c1", "未来的工具", "{}")),
            results = emptyList(),
        )
        assertTrue(steps[0].label.isNotBlank())
    }

    @Test
    fun emptyCallsGiveEmptySteps() {
        assertEquals(emptyList(), executionStepsFor(emptyList(), emptyList()))
    }
}
