package com.starringbox.staragent.core.llm

import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.LlmProtocol

/** 一次请求所需的全部信息。由服务商配置 + 当前智能体选定的模型解析得到。 */
data class LlmEndpoint(
    val protocol: LlmProtocol,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String? = null,
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
)

/**
 * 流式响应事件。
 *
 * 之所以做成事件流而不是直接返回字符串：界面需要在文字逐段到达时就渲染出来，
 * 用户第一时间看到它在说话，而不是等整段生成完再一次性出现。
 */
sealed interface ChatStreamEvent {
    data class Delta(val text: String) : ChatStreamEvent
    data object Completed : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}
