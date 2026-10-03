package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.runtime.*

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.NoopMemoryPort
import com.starringbox.staragent.core.tools.ports.NoopTaskPort
import com.starringbox.staragent.core.tools.ports.NoopWebPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.UserInputPort
import com.starringbox.staragent.core.tools.ports.WebPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val endpoint = LlmEndpoint(LlmProtocol.OpenAi, "https://example.com", "key", "model")

private class TestTrackingUserInputPort(var answerToReturn: String = "允许") : UserInputPort {
    val questionsAsked = mutableListOf<String>()

    override suspend fun requestInput(
        agentId: String,
        question: String,
        options: List<String>,
        placeholder: String?,
    ): String {
        questionsAsked += question
        return answerToReturn
    }
}

private fun scriptedStream(
    rounds: List<List<ChatStreamEvent>>,
): suspend (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent> {
    var index = 0
    return { _, _ ->
        flow {
            val events = rounds.getOrNull(index) ?: emptyList()
            index++
            events.forEach { emit(it) }
            emit(ChatStreamEvent.Completed)
        }
    }
}

class ExecutionPolicyTest {

    private val dangerousTool = object : Tool {
        override val definition = ToolDefinition("delete_everything", "危险写入操作", buildJsonObject { put("type", "object") })
        override val requiresConfirmation: Boolean = true
        var executed = false

        override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
            executed = true
            return ToolResult(callId = "", content = "所有数据已删除")
        }
    }

    private val safeTool = object : Tool {
        override val definition = ToolDefinition("read_info", "只读操作", buildJsonObject { put("type", "object") })
        override val requiresConfirmation: Boolean = false
        var executed = false

        override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
            executed = true
            return ToolResult(callId = "", content = "信息详情")
        }
    }

    private fun testHost(
        userInput: UserInputPort = TestTrackingUserInputPort(),
        executionPolicy: ExecutionPolicy = ExecutionPolicy.Auto,
    ) = ToolHost(
        memory = NoopMemoryPort,
        tasks = NoopTaskPort,
        web = NoopWebPort,
        userInput = userInput,
        executionPolicy = executionPolicy,
        agentId = "a-test",
        conversationKey = "agent:a-test",
    )

    @Test
    fun autoPolicyDirectlyExecutesDangerousToolWithoutAsking() = runTest {
        dangerousTool.executed = false
        val userInput = TestTrackingUserInputPort()
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.Auto)

        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_everything", "{}")))),
                    listOf(ChatStreamEvent.Delta("已处理")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(dangerousTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // 自动模式：直接执行，无需打扰
        assertTrue(dangerousTool.executed)
        assertEquals(0, userInput.questionsAsked.size, "自动模式下不应弹出确认卡片")
        val result = history[1].toolResults.single()
        assertFalse(result.isError)
        assertEquals("所有数据已删除", result.content)
    }

    @Test
    fun onDemandPolicyInterceptsDangerousToolAndAllowsExecutionWhenUserApproves() = runTest {
        dangerousTool.executed = false
        val userInput = TestTrackingUserInputPort(answerToReturn = "允许")
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.OnDemand)

        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_everything", "{}")))),
                    listOf(ChatStreamEvent.Delta("已处理")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(dangerousTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // 按需模式：被拦截提问，用户回答允许后执行
        assertEquals(1, userInput.questionsAsked.size, "按需模式下应弹出确认卡片")
        assertTrue(userInput.questionsAsked.single().contains("申请执行操作"))
        assertTrue(dangerousTool.executed, "用户允许后应真正执行")
        val result = history[1].toolResults.single()
        assertFalse(result.isError)
        assertEquals("所有数据已删除", result.content)
    }

    @Test
    fun onDemandPolicyCancelsDangerousToolWhenUserRejects() = runTest {
        dangerousTool.executed = false
        val userInput = TestTrackingUserInputPort(answerToReturn = "拒绝")
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.OnDemand)

        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_everything", "{}")))),
                    listOf(ChatStreamEvent.Delta("知道了")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(dangerousTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // 用户拒绝：不执行工具，回喂错误结果给模型
        assertEquals(1, userInput.questionsAsked.size)
        assertFalse(dangerousTool.executed, "用户拒绝时不应执行工具")
        val result = history[1].toolResults.single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("用户未同意"))
    }

    @Test
    fun onDemandPolicyDoesNotInterceptSafeReadOnlyTool() = runTest {
        safeTool.executed = false
        val userInput = TestTrackingUserInputPort()
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.OnDemand)

        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "read_info", "{}")))),
                    listOf(ChatStreamEvent.Delta("完成")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(safeTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // 只读安全工具不拦截
        assertTrue(safeTool.executed)
        assertEquals(0, userInput.questionsAsked.size, "安全工具不应触发提问拦截")
        val result = history[1].toolResults.single()
        assertFalse(result.isError)
    }

    @Test
    fun onDemandPolicyCancelsDangerousToolWhenConfirmationTimesOut() = runTest {
        dangerousTool.executed = false
        val slowUserInput = object : UserInputPort {
            override suspend fun requestInput(
                agentId: String,
                question: String,
                options: List<String>,
                placeholder: String?,
            ): String {
                kotlinx.coroutines.delay(500) // 远超 50ms 的确认超时
                return "允许"
            }
        }
        val host = testHost(userInput = slowUserInput, executionPolicy = ExecutionPolicy.OnDemand)

        val loop = AgentLoop(
            stream = scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_everything", "{}")))),
                    listOf(ChatStreamEvent.Delta("已取消")),
                )
            ),
            confirmationTimeoutMillis = 50L,
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(dangerousTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertFalse(dangerousTool.executed, "确认超时不应执行工具")
        val result = history[1].toolResults.single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("操作确认超时"))
    }
}
