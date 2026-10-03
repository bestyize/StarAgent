package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import com.starringbox.staragent.core.tools.ports.NoopMemoryPort
import com.starringbox.staragent.core.tools.ports.NoopTaskPort
import com.starringbox.staragent.core.tools.ports.NoopWebPort
import com.starringbox.staragent.core.tools.ports.UserInputPort
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

private class RecordingUserInputPortForAlwaysConfirm(var answerToReturn: String = "允许") : UserInputPort {
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

private fun scriptedStreamForAlwaysConfirm(
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

/**
 * `Tool.alwaysConfirm`：不管执行策略是什么，这类工具每次都要用户点头。
 *
 * 默认策略是 Auto，光靠 `requiresConfirmation` 的话，装技能、创建技能这类
 * 会留下长期影响的操作在默认设置下一次都不会被问——这正是 [Tool.alwaysConfirm] 要堵的口子。
 */
class AlwaysConfirmToolTest {

    private val alwaysConfirmTool = object : Tool {
        override val definition = ToolDefinition("install_something", "会留下长期影响的操作", buildJsonObject { put("type", "object") })
        override val requiresConfirmation: Boolean = true
        override val alwaysConfirm: Boolean = true
        var executed = false

        override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
            executed = true
            return ToolResult(callId = "", content = "已安装")
        }
    }

    private val normalConfirmTool = object : Tool {
        override val definition = ToolDefinition("delete_something", "有副作用但不是长期影响", buildJsonObject { put("type", "object") })
        override val requiresConfirmation: Boolean = true
        var executed = false

        override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
            executed = true
            return ToolResult(callId = "", content = "已删除")
        }
    }

    private fun testHost(
        userInput: UserInputPort = RecordingUserInputPortForAlwaysConfirm(),
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
    fun alwaysConfirmToolAsksEvenUnderAutoPolicy() = runTest {
        alwaysConfirmTool.executed = false
        val userInput = RecordingUserInputPortForAlwaysConfirm(answerToReturn = "允许")
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.Auto)

        val loop = AgentLoop(
            scriptedStreamForAlwaysConfirm(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "install_something", "{}")))),
                    listOf(ChatStreamEvent.Delta("已处理")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(alwaysConfirmTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(1, userInput.questionsAsked.size, "alwaysConfirm 的工具即使在 Auto 策略下也要弹卡确认")
        assertTrue(alwaysConfirmTool.executed, "用户同意后应当真正执行")
    }

    @Test
    fun alwaysConfirmToolIsCancelledUnderAutoPolicyWhenUserRejects() = runTest {
        alwaysConfirmTool.executed = false
        val userInput = RecordingUserInputPortForAlwaysConfirm(answerToReturn = "拒绝")
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.Auto)

        val loop = AgentLoop(
            scriptedStreamForAlwaysConfirm(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "install_something", "{}")))),
                    listOf(ChatStreamEvent.Delta("知道了")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(alwaysConfirmTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertFalse(alwaysConfirmTool.executed, "用户拒绝时不应执行")
        val result = history[1].toolResults.single()
        assertTrue(result.isError)
    }

    @Test
    fun normalRequiresConfirmationToolDoesNotAskUnderAutoPolicy() = runTest {
        normalConfirmTool.executed = false
        val userInput = RecordingUserInputPortForAlwaysConfirm()
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.Auto)

        val loop = AgentLoop(
            scriptedStreamForAlwaysConfirm(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_something", "{}")))),
                    listOf(ChatStreamEvent.Delta("已处理")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(normalConfirmTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // Auto 策略下，普通 requiresConfirmation（非 alwaysConfirm）的工具不该被拦——
        // 这条反向钉住上面那条不是因为 testHost 默认行为造成的假阳性
        assertEquals(0, userInput.questionsAsked.size, "普通确认工具在 Auto 策略下不该被拦")
        assertTrue(normalConfirmTool.executed)
    }

    @Test
    fun normalRequiresConfirmationToolDoesAskUnderOnDemandPolicy() = runTest {
        normalConfirmTool.executed = false
        val userInput = RecordingUserInputPortForAlwaysConfirm(answerToReturn = "允许")
        val host = testHost(userInput = userInput, executionPolicy = ExecutionPolicy.OnDemand)

        val loop = AgentLoop(
            scriptedStreamForAlwaysConfirm(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "delete_something", "{}")))),
                    listOf(ChatStreamEvent.Delta("已处理")),
                )
            )
        )

        val history = mutableListOf<LlmTurn>()
        loop.run(
            endpoint = endpoint,
            history = history,
            host = host,
            registry = ToolRegistry.of(normalConfirmTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(1, userInput.questionsAsked.size, "OnDemand 策略下普通确认工具该被拦")
        assertTrue(normalConfirmTool.executed)
    }
}
