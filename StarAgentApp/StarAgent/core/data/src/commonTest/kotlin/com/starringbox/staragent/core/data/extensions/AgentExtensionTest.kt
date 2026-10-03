package com.starringbox.staragent.core.data.extensions

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.runtime.extensions.AgentExtension
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentExtensionTest {

    private val customTool = object : Tool {
        override val definition = ToolDefinition("custom_calc", "计算器", JsonObject(emptyMap()))
        override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult = ToolResult("", "42")
    }

    private class CustomCalcExtension(private val tool: Tool) : AgentExtension {
        override val id = "custom_calc"
        override fun tools(agent: Agent): List<Tool> = listOf(tool)
        override fun contributePrompt(agent: Agent, toolsAvailable: Boolean): String? =
            if (toolsAvailable) "【专业工具提示】本智能体具备高精度数学计算扩展。" else null
    }

    @Test
    fun extensionContributesToolsAndPromptToAgent() {
        val state = AppState()
        val agent = state.agents.first()

        // 默认情况下不包含该自定义扩展
        assertFalse(state.registryFor(agent).definitions().any { it.name == "custom_calc" })
        assertFalse(state.systemPromptForTest(agent).contains("【专业工具提示】"))

        // 安装自定义扩展
        val calcExt = CustomCalcExtension(customTool)
        val customState = AppState()
        // 通过直接向 extensions 列表添加或使用派生配置
        val customExtensions = customState.extensions + calcExt

        // 验证自定义扩展贡献的工具与 Prompt
        val tools = customExtensions.flatMap { it.tools(agent) }
        assertTrue(tools.any { it.definition.name == "custom_calc" })

        val promptSections = customExtensions.mapNotNull { it.contributePrompt(agent, toolsAvailable = true) }
        assertTrue(promptSections.any { it.contains("【专业工具提示】本智能体具备高精度数学计算扩展。") })
    }

    @Test
    fun taskExtensionRespectsCanScheduleTasks() {
        val ext = TaskExtension()
        val canScheduleAgent = Agent("a1", "能建任务", "⭐", "persona", "model:0", canScheduleTasks = true)
        val cannotScheduleAgent = Agent("a2", "不能建任务", "⭐", "persona", "model:0", canScheduleTasks = false)

        val toolsWithSchedule = ext.tools(canScheduleAgent).map { it.definition.name }
        val toolsWithoutSchedule = ext.tools(cannotScheduleAgent).map { it.definition.name }

        assertTrue(toolsWithSchedule.contains("create_task"), "允许托管任务的智能体包含 create_task")
        assertTrue(toolsWithSchedule.contains("list_tasks"))

        assertFalse(toolsWithoutSchedule.contains("create_task"), "禁止托管任务的智能体不提供 create_task")
        assertTrue(toolsWithoutSchedule.contains("list_tasks"))
    }
}
