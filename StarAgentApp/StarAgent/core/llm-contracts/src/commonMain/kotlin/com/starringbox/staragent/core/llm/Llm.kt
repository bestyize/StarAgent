package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult

/** 宿主请求模型所需的信息。契约独立于 HTTP 客户端、平台及异步运行时。 */
data class LlmEndpoint(
    val protocol: LlmProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String? = null,
    /** 这次请求要提供给模型的工具。空 = 不提供工具，走纯文本协议。 */
    val tools: List<ToolDefinition> = emptyList(),
)

enum class LlmRole { User, Assistant }

/**
 * 发给模型的一轮对话。
 *
 * [images] 为空时按纯文本发；不为空时两家协议都会改发 content 块数组，
 * 但**块的结构完全不同**（见 ChatCompletion 里的两套实现）。
 */
data class LlmTurn(
    val role: LlmRole,
    val text: String,
    val images: List<MediaAsset> = emptyList(),
    /** assistant 这一轮发出的工具调用。回喂时要按原样带回，两家都要求。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 工具执行的结果，回喂给模型。两家的**放置位置不同**，见 ChatCompletion。 */
    val toolResults: List<ToolResult> = emptyList(),
)

/**
 * 流式响应事件。
 *
 * 宿主可以通过回调、Flow 或其他机制递交这些事件；事件类型本身不绑定运行时。
 */
sealed interface ChatStreamEvent {
    data class Delta(val text: String) : ChatStreamEvent

    /**
     * 这一轮里模型请求的工具调用，**整个流结束时一次性给出**。
     *
     * 粒度选"等流结束"而不是"每个 call 拼完就发"：模型这一轮只输出工具调用、
     * 不输出正文，用户本来没什么可看的；早点知道要调什么价值不大，复杂度实实在在。
     */
    data class ToolCallsReady(val calls: List<ToolCall>) : ChatStreamEvent

    data object Completed : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}
