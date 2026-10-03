package com.starringbox.staragent.core.runtime.extensions

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.tools.ToolRegistry

/** 每个宿主显式装配的扩展集合。保留顺序，拒绝含糊的扩展或工具命名冲突。 */
class AgentExtensions(extensions: List<AgentExtension>) {
    private val assembled = extensions.toList()
    val installed: List<AgentExtension> get() = assembled.toList()

    init {
        val ids = assembled.map { it.id }
        require(ids.all { it.isNotBlank() }) { "Extension ids must be non-empty" }
        require(ids.distinct().size == ids.size) { "Duplicate extension ids: $ids" }
    }

    fun registryFor(agent: Agent): ToolRegistry {
        val tools = assembled.flatMap { it.tools(agent) }
        val names = tools.map { it.definition.name }
        require(names.all { it.isNotBlank() }) { "Tool names must be non-empty" }
        require(names.distinct().size == names.size) { "Duplicate tool names in installed extensions: $names" }
        return ToolRegistry.of(*tools.toTypedArray())
    }

    fun promptSections(agent: Agent, toolsAvailable: Boolean): List<String> = assembled.mapNotNull {
        it.contributePrompt(agent, toolsAvailable)?.trim()?.takeIf(String::isNotEmpty)
    }
}
