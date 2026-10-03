package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Artifact
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnRunnerTest {

    private val endpoint = LlmEndpoint(
        protocol = LlmProtocol.OpenAi,
        baseUrl = "https://test.api",
        apiKey = "key",
        model = "test-model",
    )

    private fun dummyHost(isSuppressed: Boolean = false) = ToolHost(
        memory = NoopMemoryPort,
        tasks = NoopTaskPort,
        web = NoopWebPort,
        agentId = "test-agent",
        conversationKey = "test-conv",
    )

    @Test
    fun plainTextStreamEmitsEventsAndCompletes() = runTest {
        val stream: suspend (LlmEndpoint, List<LlmTurn>) -> kotlinx.coroutines.flow.Flow<ChatStreamEvent> = { _, _ ->
            flow {
                emit(ChatStreamEvent.Delta("你好"))
                emit(ChatStreamEvent.Delta("，我是助手。"))
                emit(ChatStreamEvent.Completed)
            }
        }

        val runner = TurnRunner(AgentLoop(stream = stream))
        val events = mutableListOf<TurnEvent>()

        val result = runner.run(
            endpoint = endpoint,
            history = listOf(LlmTurn(LlmRole.User, "你好")),
            registry = ToolRegistry.Empty,
            createHost = { isSuppressed -> dummyHost(isSuppressed()) },
            onEvent = { events += it },
        )

        assertTrue(result.outcome is LoopOutcome.Done)
        assertEquals("你好，我是助手。", result.text)
        assertEquals(0, result.calls.size)
        assertEquals(false, result.externalContextObserved)

        assertEquals(3, events.size)
        assertTrue(events[0] is TurnEvent.RoundStarted)
        assertEquals("你好", (events[1] as TurnEvent.TextChunk).chunk)
        assertEquals("，我是助手。", (events[2] as TurnEvent.TextChunk).chunk)
    }

    @Test
    fun toolExecutionEmitsStepsAndTaintsExternalContext() = runTest {
        val fakeTool = object : Tool {
            override val definition = ToolDefinition("web_fetch", "fetch", JsonObject(emptyMap()))
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                return ToolResult(
                    callId = "call-1",
                    content = "网页内容",
                    isError = false,
                    externalContext = true,
                )
            }
        }

        var turn = 0
        val stream: suspend (LlmEndpoint, List<LlmTurn>) -> kotlinx.coroutines.flow.Flow<ChatStreamEvent> = { _, _ ->
            flow {
                turn++
                if (turn == 1) {
                    emit(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("call-1", "web_fetch", "{}"))))
                } else {
                    emit(ChatStreamEvent.Delta("基于网页的总结"))
                }
                emit(ChatStreamEvent.Completed)
            }
        }

        val runner = TurnRunner(AgentLoop(stream = stream))
        val events = mutableListOf<TurnEvent>()

        val result = runner.run(
            endpoint = endpoint,
            history = listOf(LlmTurn(LlmRole.User, "查一下")),
            registry = ToolRegistry.of(fakeTool),
            createHost = { isSuppressed -> dummyHost(isSuppressed()) },
            onEvent = { events += it },
        )

        assertTrue(result.outcome is LoopOutcome.Done)
        assertEquals("基于网页的总结", result.text)
        assertEquals(1, result.calls.size)
        assertEquals(true, result.externalContextObserved, "调用返回 externalContext 时本轮被标记为受污染")

        val stepEvents = events.filterIsInstance<TurnEvent.StepsChanged>()
        assertTrue(stepEvents.isNotEmpty(), "应分发步骤更新事件")
        // 第一阶段：未完成；第二阶段：已完成
        assertEquals(false, stepEvents.first().steps.first().done)
        assertEquals(true, stepEvents.last().steps.first().done)
    }

    @Test
    fun toolArtifactIsCollectedAndDispatched() = runTest {
        val testArtifact = Artifact(
            id = "art-1",
            title = "报告",
            kind = com.starringbox.staragent.core.model.ArtifactKind.Report,
            summary = "周报",
            mimeType = "text/markdown",
            fileName = "report.md",
            agentId = "test-agent",
        )

        val fakeTool = object : Tool {
            override val definition = ToolDefinition("create_artifact", "art", JsonObject(emptyMap()))
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                return ToolResult(
                    callId = "call-2",
                    content = "已创建",
                    isError = false,
                    artifact = testArtifact,
                )
            }
        }

        var turn = 0
        val stream: suspend (LlmEndpoint, List<LlmTurn>) -> kotlinx.coroutines.flow.Flow<ChatStreamEvent> = { _, _ ->
            flow {
                turn++
                if (turn == 1) {
                    emit(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("call-2", "create_artifact", "{}"))))
                } else {
                    emit(ChatStreamEvent.Delta("完成"))
                }
                emit(ChatStreamEvent.Completed)
            }
        }

        val runner = TurnRunner(AgentLoop(stream = stream))
        val events = mutableListOf<TurnEvent>()

        val result = runner.run(
            endpoint = endpoint,
            history = listOf(LlmTurn(LlmRole.User, "写报告")),
            registry = ToolRegistry.of(fakeTool),
            createHost = { isSuppressed -> dummyHost(isSuppressed()) },
            onEvent = { events += it },
        )

        assertEquals(1, result.artifacts.size)
        assertEquals("art-1", result.artifacts.first().id)
        assertTrue(events.any { it is TurnEvent.ArtifactProduced && it.artifact.id == "art-1" })
    }

    @Test
    fun cancellationPreservesPartialTextAndArtifacts() = runTest {
        val toolEntered = CompletableDeferred<Unit>()
        val toolHold = CompletableDeferred<Unit>()

        val fakeTool = object : Tool {
            override val definition = ToolDefinition("slow_tool", "slow", JsonObject(emptyMap()))
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                toolEntered.complete(Unit)
                toolHold.await()
                return ToolResult("call-3", "ok", false)
            }
        }

        val stream: suspend (LlmEndpoint, List<LlmTurn>) -> kotlinx.coroutines.flow.Flow<ChatStreamEvent> = { _, _ ->
            flow {
                emit(ChatStreamEvent.Delta("开始执行："))
                emit(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("call-3", "slow_tool", "{}"))))
                emit(ChatStreamEvent.Completed)
            }
        }

        val runner = TurnRunner(AgentLoop(stream = stream))
        var capturedCancellation: TurnCancelledException? = null

        val job = launch {
            try {
                runner.run(
                    endpoint = endpoint,
                    history = listOf(LlmTurn(LlmRole.User, "慢任务")),
                    registry = ToolRegistry.of(fakeTool),
                    createHost = { isSuppressed -> dummyHost(isSuppressed()) },
                )
            } catch (c: TurnCancelledException) {
                capturedCancellation = c
                throw c
            }
        }

        toolEntered.await()
        job.cancel()
        job.join()

        assertTrue(capturedCancellation != null, "取消时必须抛出 TurnCancelledException")
        assertEquals("开始执行：", capturedCancellation?.accumulatedText)
    }
}

