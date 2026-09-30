package com.starringbox.staragent.core.model

import kotlinx.serialization.json.JsonObject

/**
 * 工具的静态描述。**纯数据，不认识任何协议。**
 *
 * 协议相关的形状（OpenAI 的 `{type:"function",function:{...}}`、Anthropic 的
 * `{name,description,input_schema}`）由 core:llm 的渲染器从它派生，
 * 这一层不出现任何一个协议字段名。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * 模型发出的一次工具调用。
 *
 * [arguments] 是**原始 JSON 字符串**，不在这里解析。两个理由：
 * 两家协议的原始形状都是字符串；而且官方明确警告模型不总生成合法 JSON——
 * 非法 JSON 必须是一个可处理的状态，不是异常。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * 一次工具执行的结果。有**三种表示**，因为读者有三个。
 *
 * [content] 给模型，必须完整；[logText] 给日志，可以有损、可以截断。
 * 上下文预算和事后调试是两件事，不要用同一个字符串。
 *
 * [externalContext] 为真表示这次结果含**外部来源**的内容（网页正文等），
 * 此时本轮不产生记忆——否则网页里写的"用户喜欢简洁回答"会被当成事实记下来，
 * 而且用户永远不知道。
 */
data class ToolResult(
    val callId: String,
    val content: String,
    val isError: Boolean = false,
    val externalContext: Boolean = false,
    val logText: String? = null,
)
