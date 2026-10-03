package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult

/** 核心交给宿主的下一步。实际模型、工具、计时和取消机制由宿主提供。 */
sealed interface AgentLoopStep {
    data class CallModel(val history: List<LlmTurn>) : AgentLoopStep
    data class CallTools(val calls: List<ToolCall>) : AgentLoopStep
    data class Done(val text: String) : AgentLoopStep
    data class Failed(val message: String) : AgentLoopStep
    data object IterationLimitReached : AgentLoopStep
    data object Cancelled : AgentLoopStep
}

enum class AgentLoopPhase { Ready, AwaitingModel, AwaitingTools, Finished, Cancelled }

/**
 * 无 I/O 的循环推进规则。只处理数据，不使用协程、JSON 解析器、时钟或平台 API。
 * 宿主通过 start / modelReply / toolResults 交回结果；协议外的推进会明确报错。
 * 每个执行轮拥有独立实例，由宿主串行调用。返回的数据不会暴露内部可变列表。
 */
class AgentLoopMachine(
    history: List<LlmTurn> = emptyList(),
    private val maxIterations: Int = 30,
) {
    private val turns = history.map(::snapshotTurn).toMutableList()
    private var pendingCalls = emptyList<ToolCall>()

    var phase: AgentLoopPhase = AgentLoopPhase.Ready
        private set
    var iterations: Int = 0
        private set

    init { require(maxIterations >= 0) { "maxIterations must be non-negative" } }

    fun history(): List<LlmTurn> = turns.map(::snapshotTurn)

    fun start(): AgentLoopStep {
        check(phase == AgentLoopPhase.Ready) { "Run already started: $phase" }
        return requestModel()
    }

    fun modelReply(
        text: String,
        calls: List<ToolCall> = emptyList(),
        failure: String? = null,
    ): AgentLoopStep {
        check(phase == AgentLoopPhase.AwaitingModel) { "No model response pending: $phase" }
        if (failure != null) {
            phase = AgentLoopPhase.Finished
            return AgentLoopStep.Failed(failure)
        }
        if (calls.isEmpty()) {
            phase = AgentLoopPhase.Finished
            return AgentLoopStep.Done(text)
        }
        require(calls.all { it.id.isNotBlank() } && calls.map { it.id }.distinct().size == calls.size) {
            "Tool call identifiers must be non-empty and unique within a model turn"
        }
        pendingCalls = calls.toList()
        turns += LlmTurn(LlmRole.Assistant, text, toolCalls = pendingCalls.toList())
        phase = AgentLoopPhase.AwaitingTools
        return AgentLoopStep.CallTools(pendingCalls.toList())
    }

    fun toolResults(results: List<ToolResult>): AgentLoopStep {
        check(phase == AgentLoopPhase.AwaitingTools) { "No tool results pending: $phase" }
        val resultIds = results.map { it.callId }
        require(resultIds.size == pendingCalls.size && resultIds.toSet() == pendingCalls.map { it.id }.toSet()) {
            "Results must answer every pending tool call exactly once"
        }
        // 宿主可以并行执行工具，但回喂顺序始终按原始调用顺序确定。
        val byId = results.associateBy { it.callId }
        turns += LlmTurn(LlmRole.User, "", toolResults = pendingCalls.map { byId.getValue(it.id) })
        pendingCalls = emptyList()
        return requestModel()
    }

    fun cancel(): AgentLoopStep.Cancelled {
        check(phase != AgentLoopPhase.Finished) { "Run already finished" }
        phase = AgentLoopPhase.Cancelled
        pendingCalls = emptyList()
        return AgentLoopStep.Cancelled
    }

    private fun requestModel(): AgentLoopStep {
        if (iterations >= maxIterations) {
            phase = AgentLoopPhase.Finished
            return AgentLoopStep.IterationLimitReached
        }
        iterations++
        phase = AgentLoopPhase.AwaitingModel
        return AgentLoopStep.CallModel(history())
    }

    private fun snapshotTurn(turn: LlmTurn): LlmTurn = turn.copy(
        images = turn.images.toList(),
        toolCalls = turn.toolCalls.toList(),
        toolResults = turn.toolResults.toList(),
    )
}
