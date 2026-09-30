package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 循环的结局。 */
internal sealed interface LoopOutcome {
    /** 模型不再要工具了，[text] 是它最后说的那段话。 */
    data class Done(val text: String) : LoopOutcome

    /** 撞上步数上限。[reason] 是给用户看的话。 */
    data class Stopped(val reason: String) : LoopOutcome

    data class Failed(val message: String) : LoopOutcome
}

/**
 * 一次多步执行：请求 → 调工具 → 回喂 → 再请求，直到模型不再要工具。
 *
 * [stream] 是**注入**的，不是直接调 ChatCompletion——这样循环能用假 LLM 单测，
 * 不用起 HTTP。这是它能被测的唯一前提。
 *
 * 工具是**串行**执行的：每个工具都可能是一次网络往返，串行让成本可预测，
 * 也跟现有"群里成员依次发言"的形状一致。并行是后续的优化。
 */
internal class AgentLoop(
    private val stream: suspend (LlmEndpoint, List<LlmTurn>) -> Flow<ChatStreamEvent>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {

    suspend fun run(
        endpoint: LlmEndpoint,
        history: MutableList<LlmTurn>,
        host: ToolHost,
        registry: ToolRegistry,
        onText: (String) -> Unit,
        onToolStart: (ToolCall) -> Unit,
        onToolEnd: (ToolCall, ToolResult) -> Unit,
    ): LoopOutcome {
        var iteration = 0

        while (true) {
            iteration++
            if (iteration > maxIterations) {
                // 明确告诉用户，不要静默截断——他得知道为什么停在这
                return LoopOutcome.Stopped(
                    "这个任务需要的步骤有点多，我先停在这里。你可以把要求说得更具体一些。"
                )
            }

            val buffer = StringBuilder()
            var calls: List<ToolCall> = emptyList()
            var failure: String? = null

            stream(endpoint, history.toList()).collect { event ->
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

            failure?.let { return LoopOutcome.Failed(it) }

            val spoken = buffer.toString()

            if (calls.isEmpty()) {
                // 终止条件：模型不再要工具了。这一轮没有工具调用，不必写回历史——
                // 回喂只发生在"还要继续"的那几轮里，收尾的这轮写进去也没人再看。
                return LoopOutcome.Done(spoken)
            }

            // assistant 这一轮要按原样写回历史：两家回喂时都要求把带工具调用的
            // assistant 轮带上，否则模型看到自己"没说过"那些调用，会重复请求。
            history += LlmTurn(
                role = LlmRole.Assistant,
                text = spoken,
                toolCalls = calls,
            )

            val results = calls.map { call ->
                onToolStart(call)
                val result = execute(call, host, registry)
                onToolEnd(call, result)
                result
            }

            // 工具结果作为一轮回喂。两家的**放置位置不同**由 core:llm 内部处理，
            // 这里只是把结果挂到 turn 上。
            history += LlmTurn(
                role = LlmRole.User,
                text = "",
                toolResults = results,
            )
        }
    }

    /**
     * 执行一次调用。**所有失败都变成结果，不变成异常**——
     * 工具的错误是给模型看的信息，它看到"参数格式错误"会自己重试；
     * 异常穿出去只会让用户看到崩溃。
     */
    private suspend fun execute(
        call: ToolCall,
        host: ToolHost,
        registry: ToolRegistry,
    ): ToolResult {
        val tool = registry.find(call.name)
            ?: return ToolResult(
                callId = call.id,
                content = "没有名为「${call.name}」的工具。可用的有：" +
                    registry.definitions().joinToString("、") { it.name },
                isError = true,
            )

        val args = parseArguments(call.arguments)
            ?: return ToolResult(
                callId = call.id,
                content = "参数不是合法的 JSON，请重新调用并确保参数是一个 JSON 对象。",
                isError = true,
            )

        return runCatching { tool.execute(args, host) }
            .getOrElse { error ->
                ToolResult(
                    callId = call.id,
                    content = "工具执行出错：${error.message ?: error::class.simpleName}",
                    isError = true,
                )
            }
            .copy(callId = call.id) // 工具自己不知道 callId，在这里补上
    }

    /** 参数解析失败返回 null，由调用方变成一条错误结果——不抛。 */
    private fun parseArguments(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrNull()

    companion object {
        /**
         * 步数上限。
         *
         * 8 是个折中：够"查资料 → 读正文 → 记住 → 回答"这种多步任务，
         * 又不至于在模型死循环时烧掉太多 token。
         */
        const val DEFAULT_MAX_ITERATIONS = 8
    }
}
