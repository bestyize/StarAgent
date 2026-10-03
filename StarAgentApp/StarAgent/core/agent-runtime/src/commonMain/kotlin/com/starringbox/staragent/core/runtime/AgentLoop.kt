package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Kotlin 宿主返回的执行结果。 */
sealed interface LoopOutcome {
    data class Done(val text: String) : LoopOutcome
    data class Stopped(val reason: String) : LoopOutcome
    data class Failed(val message: String) : LoopOutcome
}

/**
 * Kotlin 协程宿主：收集模型流、执行工具、发出观察事件并完成资源收尾。
 * 推进规则由 AgentLoopMachine 负责；模型、工具执行和释放机制均可注入。
 */
class AgentLoop(
    private val stream: suspend (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    toolTimeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MILLIS,
    confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
    toolExecutor: ToolCallExecutor? = null,
) {
    private val executor = toolExecutor ?: DefaultToolCallExecutor(toolTimeoutMillis, confirmationTimeoutMillis)

    suspend fun run(
        endpoint: LlmEndpoint,
        history: MutableList<LlmTurn>,
        host: ToolHost,
        registry: ToolRegistry,
        onText: suspend (String) -> Unit,
        onToolStart: suspend (ToolCall) -> Unit = {},
        onToolEnd: suspend (ToolCall, ToolResult) -> Unit = { _, _ -> },
        onRoundStarted: suspend () -> Unit = {},
    ): LoopOutcome {
        val machine = AgentLoopMachine(history, maxIterations)
        fun updateHistory() {
            history.clear()
            history.addAll(machine.history())
        }
        try {
            var step = machine.start()
            while (true) {
                step = when (val next = step) {
                    is AgentLoopStep.CallModel -> {
                        onRoundStarted()
                        val buffer = StringBuilder()
                        var calls = emptyList<ToolCall>()
                        var failure: String? = null
                        stream(endpoint, next.history).collect { event ->
                            when (event) {
                                is ChatStreamEvent.Delta -> {
                                    buffer.append(event.text)
                                    onText(event.text)
                                }
                                is ChatStreamEvent.ToolCallsReady -> calls = event.calls
                                is ChatStreamEvent.Failed -> failure = event.message
                                ChatStreamEvent.Completed -> Unit
                            }
                        }
                        machine.modelReply(buffer.toString(), calls, failure).also { updateHistory() }
                    }
                    is AgentLoopStep.CallTools -> {
                        val results = next.calls.map { call ->
                            onToolStart(call)
                            val result = executor.execute(call, host, registry).copy(callId = call.id)
                            onToolEnd(call, result)
                            result
                        }
                        machine.toolResults(results).also { updateHistory() }
                    }
                    is AgentLoopStep.Done -> return LoopOutcome.Done(next.text)
                    is AgentLoopStep.Failed -> return LoopOutcome.Failed(next.message)
                    AgentLoopStep.IterationLimitReached -> return LoopOutcome.Stopped(
                        "这个任务需要的步骤有点多，我先停在这里。你可以把要求说得更具体一些。"
                    )
                    AgentLoopStep.Cancelled -> throw CancellationException("Run cancelled")
                }
            }
        } catch (cancelled: CancellationException) {
            if (machine.phase != AgentLoopPhase.Finished) machine.cancel()
            throw cancelled
        } catch (error: Throwable) {
            return LoopOutcome.Failed("调用模型出错了：${error.message ?: error::class.simpleName}")
        } finally {
            withContext(NonCancellable) { host.onTurnFinished() }
        }
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 30
        const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
