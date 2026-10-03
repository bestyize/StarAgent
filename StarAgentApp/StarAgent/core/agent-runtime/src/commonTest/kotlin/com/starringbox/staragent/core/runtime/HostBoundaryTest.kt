package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HostBoundaryTest {
    @Test
    fun negativeOrAmbiguousTextNeverAuthorizesAnAlwaysConfirmTool() = runTest {
        for (answer in listOf("允许", " 允许 ", "不允许", "不允许执行", "允许？", "请不要允许", "拒绝", "")) {
            var executed = 0
            val tool = object : Tool {
                override val definition = ToolDefinition("write", "write", JsonObject(emptyMap()))
                override val alwaysConfirm = true
                override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                    executed++
                    return ToolResult("", "written")
                }
            }
            val input = object : UserInputPort {
                override suspend fun requestInput(agentId: String, question: String, options: List<String>, placeholder: String?) = answer
            }
            val result = DefaultToolCallExecutor().execute(ToolCall("call", "write", "{}"), host(input), ToolRegistry.of(tool))
            val approved = answer.trim() == "允许"
            assertEquals(if (approved) 1 else 0, executed, answer)
            assertEquals(!approved, result.isError, answer)
        }
    }

    @Test
    fun hostCanProvideStructuredConfirmationWithoutAnyUiPort() = runTest {
        var executed = false
        val tool = object : Tool {
            override val definition = ToolDefinition("write", "write", JsonObject(emptyMap()))
            override val alwaysConfirm = true
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                executed = true
                return ToolResult("", "written")
            }
        }
        val executor = DefaultToolCallExecutor(confirmation = ToolConfirmationPort { _, _ -> ConfirmationDecision.Denied })
        val denied = executor.execute(ToolCall("call", "write", "{}"), host(), ToolRegistry.of(tool))
        assertEquals(true, denied.isError)
        assertFalse(executed)
        val approved = DefaultToolCallExecutor(confirmation = ToolConfirmationPort { _, _ -> ConfirmationDecision.Approved })
            .execute(ToolCall("call", "write", "{}"), host(), ToolRegistry.of(tool))
        assertEquals("call", approved.callId)
        assertEquals(true, executed)
    }

    @Test
    fun cancellationCompletesSuspendingCleanupExactlyOnce() = runTest {
        val entered = CompletableDeferred<Unit>()
        var released = 0
        val loop = AgentLoop(stream = { _, _ -> flow<ChatStreamEvent> { entered.complete(Unit); awaitCancellation() } })
        val job = launch {
            loop.run(
                endpoint = LlmEndpoint(LlmProtocol.OpenAi, "", "", "test"),
                history = mutableListOf(),
                host = host(onFinish = { delay(10); released++ }),
                registry = ToolRegistry.Empty,
                onText = {},
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, released)
    }

    @Test
    fun toolNamesCannotBypassTheDeclaredConfirmationPolicy() = runTest {
        var executed = false
        val tool = object : Tool {
            override val definition = ToolDefinition("device_use", "custom tool", JsonObject(emptyMap()))
            override val requiresConfirmation = true
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                executed = true
                return ToolResult("", "written")
            }
        }
        val executor = DefaultToolCallExecutor(confirmation = ToolConfirmationPort { _, _ -> ConfirmationDecision.Denied })
        val denied = executor.execute(
            ToolCall("call", "device_use", "{\"operation\":\"list_apps\"}"),
            host(policy = ExecutionPolicy.OnDemand), ToolRegistry.of(tool),
        )
        assertEquals(true, denied.isError)
        assertFalse(executed)
    }

    private fun host(
        userInput: UserInputPort = com.starringbox.staragent.core.tools.ports.NoopUserInputPort,
        onFinish: suspend () -> Unit = {},
        policy: ExecutionPolicy = ExecutionPolicy.Auto,
    ) = ToolHost(
        memory = NoopMemoryPort, tasks = NoopTaskPort, web = NoopWebPort,
        userInput = userInput, executionPolicy = policy,
        agentId = "test", conversationKey = "test", onTurnFinished = onFinish,
    )
}
