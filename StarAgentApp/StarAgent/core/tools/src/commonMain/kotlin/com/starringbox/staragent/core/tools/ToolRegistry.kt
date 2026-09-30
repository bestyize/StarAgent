package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ToolDefinition

/**
 * 工具注册表。
 *
 * 一期只有内置工具，但形状跟 core:platform 的 MediaPicker / MediaSaver /
 * LocalStore 一样是"契约 + 注册表"——三期接外部工具（MCP）时不用改结构，
 * 只是多一个注册来源。
 *
 * 保留注册顺序：模型看到的工具清单顺序会影响它的选择，顺序必须是稳定的。
 */
class ToolRegistry private constructor(private val tools: List<Tool>) {

    fun find(name: String): Tool? = tools.firstOrNull { it.definition.name == name }

    fun definitions(): List<ToolDefinition> = tools.map { it.definition }

    operator fun plus(other: ToolRegistry): ToolRegistry =
        ToolRegistry(tools + other.tools.filterNot { incoming ->
            tools.any { it.definition.name == incoming.definition.name }
        })

    companion object {
        fun of(vararg tools: Tool): ToolRegistry = ToolRegistry(tools.toList())

        val Empty: ToolRegistry = ToolRegistry(emptyList())
    }
}
