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

    /**
     * 已完成的判定：结果里出现了这个 callId 才算 done。
     *
     * 这条断言**要能失败**：只调一次工具时，一条结果都没回来就必须是
     * `done == false`。原来的写法 `it.done || steps.size > 1` 在 `size == 1` 时恒真
     * （`steps.size > 1` 为假、`it.done` 是唯一变量），把实现改成永远 `done = true`
     * 也照样过——那不是在测任何东西。
     */
    @Test
    fun marksFinishedStepsAsDone() {
        val pending = executionStepsFor(
            calls = listOf(ToolCall("c1", "remember", "{}")),
            results = emptyList(),
        )
        assertEquals(listOf(false), pending.map { it.done }, "还没有结果的步骤不该被当成已完成")

        val finished = executionStepsFor(
            calls = listOf(ToolCall("c1", "remember", "{}")),
            results = listOf(ToolResult(callId = "c1", content = "已记住")),
        )
        assertEquals(listOf(true), finished.map { it.done }, "拿到结果的步骤该打勾")
    }

    /**
     * 没见过的工具也得有个说法——**而且是它自己的名字**。
     *
     * 只断言 `isNotBlank()` 太松：实现把未知工具一律显示成"处理中"也能过，
     * 而那正好是 brief 说的"退回它的名字，而不是留空"想避免的反面。
     */
    @Test
    fun unknownToolFallsBackToItsOwnName() {
        val steps = executionStepsFor(
            calls = listOf(ToolCall("c1", "未来的工具", "{}")),
            results = emptyList(),
        )
        assertEquals("未来的工具", steps.single().label)
    }

    @Test
    fun emptyCallsGiveEmptySteps() {
        assertEquals(emptyList(), executionStepsFor(emptyList(), emptyList()))
    }
}
