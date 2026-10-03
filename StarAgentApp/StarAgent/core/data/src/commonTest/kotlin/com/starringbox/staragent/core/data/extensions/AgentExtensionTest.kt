package com.starringbox.staragent.core.data.extensions

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.data.MemoryStore
import com.starringbox.staragent.core.data.SettingsSandbox
import com.starringbox.staragent.core.llm.ChatStreamEvent
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.runtime.extensions.AgentExtension
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.McpRegistry
import com.starringbox.staragent.core.tools.McpToolDescriptor
import com.starringbox.staragent.core.tools.ports.McpPort
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
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
        val customState = AppState(extensionFactory = { app -> builtinExtensions(app) + calcExt })

        // 验证自定义扩展贡献的工具与 Prompt
        assertTrue(customState.registryFor(agent).definitions().any { it.name == "custom_calc" })

        assertTrue(customState.systemPromptForTest(agent).contains("【专业工具提示】本智能体具备高精度数学计算扩展。"))
    }

    @Test
    fun productCanReplaceDefaultExtensions() {
        val state = AppState(extensionFactory = { listOf(CustomCalcExtension(customTool)) })
        val agent = state.agents.first()
        assertEquals(listOf("custom_calc"), state.extensions.map { it.id })
        assertEquals(listOf("custom_calc"), state.registryFor(agent).definitions().map { it.name })
        assertFalse(state.systemPromptForTest(agent).contains("【移动端设备操作与系统权限规范】"))
    }

    @Test
    fun connectedMcpToolsOnlyAppearWhenMcpExtensionIsInstalled() {
        val serverId = "extension_test"
        val port = object : McpPort {
            override suspend fun call(serverId: String, toolName: String, arguments: JsonObject, agentId: String) =
                error("This test only inspects registration")
        }
        McpRegistry.install(port, listOf(McpToolDescriptor(serverId, "ping", "ping", JsonObject(emptyMap()))))
        try {
            val isolated = AppState(extensionFactory = { listOf(CustomCalcExtension(customTool)) })
            assertEquals(listOf("custom_calc"), isolated.registryFor(isolated.agents.first()).definitions().map { it.name })
            val withMcp = AppState(extensionFactory = { listOf(McpExtension()) })
            assertTrue(withMcp.registryFor(withMcp.agents.first()).definitions().any { it.name == "mcp__extension_test__ping" })
        } finally {
            McpRegistry.remove(serverId)
        }
    }

    @Test
    fun installedExtensionIsAdvertisedAndExecutedDuringAnActualAppTurn() = runTest {
        val sandbox = SettingsSandbox(listOf("apikey:deepseek", "models:deepseek"))
        sandbox.enter()
        LocalStores.install(MemoryStore())
        try {
            var executed = 0
            val tool = object : Tool {
                override val definition = customTool.definition
                override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
                    executed++
                    return ToolResult("", "42")
                }
            }
            var rounds = 0
            val state = AppState(
                extensionFactory = { app -> builtinExtensions(app) + CustomCalcExtension(tool) },
                stream = { endpoint, history -> flow {
                    assertTrue(endpoint.tools.any { it.name == "custom_calc" })
                    assertTrue(endpoint.systemPrompt.orEmpty().contains("【专业工具提示】"))
                    if (rounds++ == 0) {
                        emit(ChatStreamEvent.ToolCallsReady(listOf(ToolCall("custom-1", "custom_calc", "{}"))))
                    } else {
                        assertEquals("42", history.last().toolResults.single().content)
                        emit(ChatStreamEvent.Delta("计算结果是 42"))
                    }
                    emit(ChatStreamEvent.Completed)
                } },
            )
            state.updateProvider(requireNotNull(state.providerById("deepseek")).copy(apiKey = "test-key", models = listOf("test-model")))
            val agent = state.agents.first()
            state.send(agent.id, "计算一下", readAloud = false)
            assertEquals(1, executed)
            assertEquals(2, rounds)
            assertEquals("计算结果是 42", (state.messagesOf(agent.id).last().body as MessageBody.Text).text)
        } finally {
            sandbox.leave()
            LocalStores.install(MemoryStore())
        }
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
