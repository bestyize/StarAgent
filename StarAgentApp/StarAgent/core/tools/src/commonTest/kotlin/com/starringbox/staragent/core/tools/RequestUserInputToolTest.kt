package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.tools.builtin.RequestUserInputTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

class RequestUserInputToolTest {

    @Test
    fun declaresFiveMinuteTimeout() {
        assertEquals(300_000L, RequestUserInputTool.timeoutMillis)
    }

    @Test
    fun passesQuestionOptionsAndPlaceholderToPort() = runTest {
        val fakeInput = FakeUserInputPort(answerToReturn = "09:00 出发")
        val host = testHost(userInput = fakeInput, agentId = "a-xing")

        val result = RequestUserInputTool.execute(
            args("""
                {
                    "question": "您希望什么时候出发？",
                    "options": ["08:00", "09:00", "10:00"],
                    "placeholder": "输入自定义时刻"
                }
            """.trimIndent()),
            host,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("09:00 出发"))

        assertEquals(1, fakeInput.requests.size)
        val req = fakeInput.requests.single()
        assertEquals("a-xing", req.agentId)
        assertEquals("您希望什么时候出发？", req.question)
        assertEquals(listOf("08:00", "09:00", "10:00"), req.options)
        assertEquals("输入自定义时刻", req.placeholder)
    }

    @Test
    fun rejectsBlankOrMissingQuestion() = runTest {
        val host = testHost()
        assertTrue(RequestUserInputTool.execute(args("""{}"""), host).isError)
        assertTrue(RequestUserInputTool.execute(args("""{"question": "  "}"""), host).isError)
    }

    @Test
    fun allowsEmptyOptionsAndNullPlaceholder() = runTest {
        val fakeInput = FakeUserInputPort("已确认")
        val host = testHost(userInput = fakeInput)

        val result = RequestUserInputTool.execute(
            args("""{"question": "确认继续吗？"}"""),
            host,
        )

        assertFalse(result.isError)
        val req = fakeInput.requests.single()
        assertEquals(emptyList(), req.options)
        assertEquals(null, req.placeholder)
    }
}
