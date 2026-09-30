package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.MemoryCategory
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import com.starringbox.staragent.core.tools.ports.FetchResult
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.TaskSummary
import com.starringbox.staragent.core.tools.ports.WebPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val endpoint = LlmEndpoint(LlmProtocol.OpenAi, "https://x", "k", "m")

/** 一个只会在注册表里找到自己的哑工具。 */
private fun stubTool(name: String, result: String = "ok") = object : Tool {
    override val definition = ToolDefinition(
        name = name,
        description = "",
        inputSchema = buildJsonObject { put("type", "object") },
    )
    override suspend fun execute(args: JsonObject, host: ToolHost) =
        ToolResult(callId = "", content = result)
}

/**
 * 假 LLM：按脚本依次给出一轮响应。
 *
 * 每一"轮"是一个事件列表。脚本用完就当作不再有工具调用（自然收尾）。
 */
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

private fun textOf(vararg parts: String) = parts.map { ChatStreamEvent.Delta(it) }

class AgentLoopTest {

    @Test
    fun returnsTextWhenModelCallsNoTools() = runTest {
        val loop = AgentLoop(scriptedStream(listOf(textOf("你好"))))
        val chunks = mutableListOf<String>()

        val outcome = loop.run(
            endpoint = endpoint,
            history = mutableListOf(),
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = { chunks += it },
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(LoopOutcome.Done("你好"), outcome)
        assertEquals(listOf("你好"), chunks)
    }

    @Test
    fun executesToolThenContinuesToFinalAnswer() = runTest {
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(
                        ChatStreamEvent.Delta("我查一下"),
                        ChatStreamEvent.ToolCallsReady(
                            listOf(ToolCall("c1", "remember", "{\"text\":\"爱吃辣\"}"))
                        ),
                    ),
                    textOf("记住了"),
                )
            )
        )
        val starts = mutableListOf<String>()
        val ends = mutableListOf<String>()
        val history = mutableListOf<LlmTurn>()

        val outcome = loop.run(
            endpoint = endpoint,
            history = history,
            host = testHost(),
            registry = ToolRegistry.of(stubTool("remember")),
            onText = {},
            onToolStart = { starts += it.name },
            onToolEnd = { _, result -> ends += result.content },
        )

        assertEquals(LoopOutcome.Done("记住了"), outcome)
        assertEquals(listOf("remember"), starts)
        assertEquals(listOf("ok"), ends)
        // 工具自己不知道 callId（stub 返回的是空串），循环必须背填成模型给的那个。
        // 这是线上的关联键，发出去是 tool_call_id——空串会被真实 API 拒掉，
        // 而少了这条断言，删掉背填整个测试套件都发现不了。
        assertEquals("c1", history[1].toolResults.single().callId)
    }

    @Test
    fun appendsAssistantTurnAndToolResultsToHistory() = runTest {
        // 回喂得靠 history，不写回去下一轮模型就看不见工具结果
        val history = mutableListOf<LlmTurn>()
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "t", "{}")))),
                    textOf("完了"),
                )
            )
        )

        loop.run(
            endpoint = endpoint,
            history = history,
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        // 一条 assistant（带 toolCalls）+ 一条带回喂结果
        assertEquals(2, history.size)
        assertEquals(listOf("t"), history[0].toolCalls.map { it.name })
        assertEquals(listOf("ok"), history[1].toolResults.map { it.content })
    }

    @Test
    fun stopsAtIterationLimitAndSaysSo() = runTest {
        // 无限循环调工具：必须在上限处停下，而不是一直烧钱
        val always = List(20) {
            listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c$it", "t", "{}"))))
        }
        val loop = AgentLoop(scriptedStream(always), maxIterations = 3)
        val starts = mutableListOf<String>()

        val outcome = loop.run(
            endpoint = endpoint,
            history = mutableListOf(),
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = {},
            onToolStart = { starts += it.name },
            onToolEnd = { _, _ -> },
        )

        assertTrue(outcome is LoopOutcome.Stopped)
        // 提示要能看懂，不能是"error"或空串
        assertTrue((outcome as LoopOutcome.Stopped).reason.isNotBlank())
        // 上限必须**精确**是 3 次请求：脚本是每轮都调工具，所以每轮恰好一次执行。
        // 这条断言钉住边界——把比较写成 `iteration >= maxIterations` 会少发一次请求
        // （上限变成 2），光看"停下来了"发现不了。
        assertEquals(3, starts.size)
    }

    @Test
    fun malformedArgumentsBecomeAnErrorResultNotACrash() = runTest {
        // 模型吐了非法 JSON。循环必须活下来，并把错误**回喂**给模型让它重试。
        val history = mutableListOf<LlmTurn>()
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "t", "{oops")))),
                    textOf("我重试"),
                )
            )
        )

        val outcome = loop.run(
            endpoint = endpoint,
            history = history,
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(LoopOutcome.Done("我重试"), outcome)
        val fedBack = history[1].toolResults.single()
        assertTrue(fedBack.isError, "非法 JSON 要标成错误，模型才知道要改")
    }

    @Test
    fun unknownToolNameBecomesAnErrorResultListingAvailableTools() = runTest {
        val history = mutableListOf<LlmTurn>()
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "没这个工具", "{}")))),
                    textOf("好"),
                )
            )
        )

        loop.run(
            endpoint = endpoint,
            history = history,
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        val fedBack = history[1].toolResults.single()
        assertTrue(fedBack.isError)
        assertTrue(fedBack.content.contains("没这个工具"))
        // 错误路径也要带上模型给的 callId——工具没找到、结果是我们自己造的，
        // 但关联键仍然得对得上，否则模型分不清这是哪一次调用的回复。
        assertEquals("c1", fedBack.callId)
    }

    @Test
    fun toolThrowingDoesNotEscapeTheLoop() = runTest {
        // 工具实现里的异常不许穿出去——穿出去就是用户看到崩溃
        val boomTool = object : Tool {
            override val definition = ToolDefinition("boom", "", buildJsonObject { put("type", "object") })
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult =
                throw IllegalStateException("炸了")
        }
        val history = mutableListOf<LlmTurn>()
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "boom", "{}")))),
                    textOf("还好"),
                )
            )
        )

        val outcome = loop.run(
            endpoint = endpoint,
            history = history,
            host = testHost(),
            // 这个测试要的是"工具自己抛异常"，所以 boomTool 必须真的注册进去——
            // 不注册的话测到的会是"工具不存在"那条分支，白测
            registry = ToolRegistry.of(boomTool),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(LoopOutcome.Done("还好"), outcome)
        assertTrue(history[1].toolResults.single().isError)
    }

    @Test
    fun streamFailureEndsTheLoopWithFailed() = runTest {
        val loop = AgentLoop(
            scriptedStream(listOf(listOf(ChatStreamEvent.Failed("网络断了"))))
        )

        val outcome = loop.run(
            endpoint = endpoint,
            history = mutableListOf(),
            host = testHost(),
            registry = ToolRegistry.of(stubTool("t")),
            onText = {},
            onToolStart = {},
            onToolEnd = { _, _ -> },
        )

        assertEquals(LoopOutcome.Failed("网络断了"), outcome)
    }

    @Test
    fun toolCancellationPropagatesInsteadOfBecomingAResult() = runTest {
        // 用户点了停止。取消不是"工具出错"——被吞掉的话循环会带着一条错误结果
        // 继续跑，用户按了停止却停不下来。取消必须原样抛出去。
        val cancellingTool = object : Tool {
            override val definition =
                ToolDefinition("slow", "", buildJsonObject { put("type", "object") })
            override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult =
                throw CancellationException("用户停止了")
        }
        val history = mutableListOf<LlmTurn>()
        val loop = AgentLoop(
            scriptedStream(
                listOf(
                    listOf(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("c1", "slow", "{}")))),
                    textOf("不该跑到这"),
                )
            )
        )

        assertFailsWith<CancellationException> {
            loop.run(
                endpoint = endpoint,
                history = history,
                host = testHost(),
                registry = ToolRegistry.of(cancellingTool),
                onText = {},
                onToolStart = {},
                onToolEnd = { _, _ -> },
            )
        }
    }
}

/**
 * 这个测试文件只关心循环，不关心端口返回了什么——所以三个端口都给空实现。
 *
 * 就地写一份而不是复用 `core:tools` commonTest 里的 `FakePorts`：
 * 跨模块的 test 源集互相看不见，那是测试实现，不是模块契约。
 */
private fun testHost() = ToolHost(
    memory = NoopMemoryPort,
    tasks = NoopTaskPort,
    web = NoopWebPort,
    agentId = "a-xing",
    conversationKey = "agent:a-xing",
)

private object NoopMemoryPort : MemoryPort {
    override suspend fun recall(agentId: String, query: String, limit: Int) = emptyList<MemoryItem>()
    override suspend fun remember(agentId: String, category: MemoryCategory, text: String) = Unit
}

private object NoopTaskPort : TaskPort {
    override suspend fun create(
        agentId: String, title: String, recurrence: Recurrence,
        scheduleLabel: String, note: String?,
    ) = "task-1"
    override suspend fun list(agentId: String) = emptyList<TaskSummary>()
    override suspend fun update(agentId: String, id: String, title: String?, scheduleLabel: String?) = true
    override suspend fun cancel(agentId: String, id: String) = true
}

private object NoopWebPort : WebPort {
    override suspend fun fetch(url: String) = FetchResult(true, "")
}
