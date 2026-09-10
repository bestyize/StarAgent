package com.starringbox.staragent.core.llm

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

/** 发给模型的一轮对话。只保留角色与文本，界面层的卡片等结构不参与请求。 */
data class LlmTurn(val role: LlmRole, val text: String)

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
