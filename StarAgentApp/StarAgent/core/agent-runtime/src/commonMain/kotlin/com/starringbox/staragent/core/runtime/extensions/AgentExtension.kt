package com.starringbox.staragent.core.runtime.extensions

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.tools.Tool

/**
 * Agent 可插拔扩展能力标准接口（SPI）。
 *
 * 每个独立能力领域（记忆、技能、任务调度、设备控制、网页检索、文件产物等）
 * 作为一个独立的 [AgentExtension] 实现：
 *
 * 1. [tools]：向智能体按需提供工具集合（可根据智能体属性动态提供，如 canScheduleTasks）；
 * 2. [contributePrompt]：向系统提示词（System Prompt）贡献该领域的上下文与行为规范说明。
 *
 * 核心编排引擎只需遍历已安装的扩展列表，无需硬编码各业务领域的 Prompt 拼接与工具白名单。
 */
interface AgentExtension {
    /** 扩展的唯一标识。 */
    val id: String

    /** 为指定的智能体提供工具列表。默认返回空列表。 */
    fun tools(agent: Agent): List<Tool> = emptyList()

    /**
     * 向系统提示词中追加说明片段。
     *
     * @param agent 当前正在对话的智能体配置。
     * @param toolsAvailable 本轮是否能够发出工具调用（false 表示降级为纯文本交互）。
     * @return 注入的提示词段落，为 null 或空白时跳过。
     */
    fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? = null
}
