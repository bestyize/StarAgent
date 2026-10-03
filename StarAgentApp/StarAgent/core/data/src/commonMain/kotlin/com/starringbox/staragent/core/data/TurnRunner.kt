package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * 单轮 Agent 执行产生的流式事件。
 *
 * 界面层（卡片更新、流式文本显示）、语音层（流式 TTS）订阅这些事件，
 * 实现表现层与核心调度解耦。
 */
internal sealed interface TurnEvent {
    /** 新一轮模型调用开始（用于流式语音打断分句/排队边界）。 */
    data object RoundStarted : TurnEvent

    /** 流式吐出的文本分片。 */
    data class TextChunk(val chunk: String) : TurnEvent

    /** 工具调用状态变化（步骤更新）。 */
    data class StepsChanged(val steps: List<ExecutionStep>) : TurnEvent

    /** 执行过程中产生的新产物文件。 */
    data class ArtifactProduced(val artifact: Artifact) : TurnEvent
}

/** 单轮 Agent 执行完毕的最终结果。 */
internal data class TurnResult(
    val outcome: LoopOutcome,
    /** 累积接收到的文本（Done 时的完整回复文本，或截断时的已有文本）。 */
    val text: String,
    /** 本轮执行过的所有工具调用。 */
    val calls: List<ToolCall>,
    /** 本轮执行过的所有工具结果。 */
    val results: List<ToolResult>,
    /** 本轮产生的所有产物。 */
    val artifacts: List<Artifact>,
    /** 本轮执行是否读取过不受信的外部内容（网页等），供记忆/技能等安全闸门消费。 */
    val externalContextObserved: Boolean,
)

/**
 * 驱动一轮 Agent 思考与行动的通用编排器。
 *
 * 统一管理：
 * 1. AgentLoop 的迭代循环；
 * 2. 外部内容污染标记（externalContextObserved）的追踪与传播；
 * 3. 工具执行步骤（ExecutionStep）的计算与事件分发；
 * 4. 产物（Artifact）的收集与事件分发；
 * 5. 用户取消（CancellationException）时的局部状态保全。
 *
 * 纯 Kotlin 逻辑，不依赖 Compose 状态，单聊、群聊、定时任务均复用此类。
 */
internal class TurnRunner(
    private val loop: AgentLoop,
) {
    suspend fun run(
        endpoint: LlmEndpoint,
        history: List<LlmTurn>,
        registry: ToolRegistry,
        createHost: (isExternalFetchObserved: () -> Boolean) -> ToolHost,
        onEvent: suspend (TurnEvent) -> Unit = {},
    ): TurnResult {
        var externalFetchThisTurn = false
        val stepCalls = mutableListOf<ToolCall>()
        val stepResults = mutableListOf<ToolResult>()
        val generatedArtifacts = mutableListOf<Artifact>()
        val buffer = StringBuilder()

        val host = createHost { externalFetchThisTurn }

        val outcome = try {
            loop.run(
                endpoint = endpoint,
                history = history.toMutableList(),
                host = host,
                registry = registry,
                onRoundStarted = { onEvent(TurnEvent.RoundStarted) },
                onText = { chunk ->
                    buffer.append(chunk)
                    onEvent(TurnEvent.TextChunk(chunk))
                },
                onToolStart = { call ->
                    stepCalls += call
                    onEvent(TurnEvent.StepsChanged(executionStepsFor(stepCalls, stepResults)))
                },
                onToolEnd = { _, result ->
                    stepResults += result
                    if (result.externalContext) externalFetchThisTurn = true
                    result.artifact?.takeUnless { result.isError }?.let {
                        generatedArtifacts += it
                        onEvent(TurnEvent.ArtifactProduced(it))
                    }
                    onEvent(TurnEvent.StepsChanged(executionStepsFor(stepCalls, stepResults)))
                },
            )
        } catch (cancelled: CancellationException) {
            throw TurnCancelledException(
                accumulatedText = buffer.toString(),
                artifacts = generatedArtifacts.toList(),
                cause = cancelled,
            )
        }

        return TurnResult(
            outcome = outcome,
            text = outcome.let { if (it is LoopOutcome.Done) it.text else buffer.toString() },
            calls = stepCalls.toList(),
            results = stepResults.toList(),
            artifacts = generatedArtifacts.toList(),
            externalContextObserved = externalFetchThisTurn,
        )
    }
}

/** 取消时携带已经产出的部分文字和产物，调用方保存后必须继续向外抛出 [cause]。 */
class TurnCancelledException(
    val accumulatedText: String,
    val artifacts: List<Artifact>,
    override val cause: CancellationException,
) : CancellationException("Turn was cancelled")
