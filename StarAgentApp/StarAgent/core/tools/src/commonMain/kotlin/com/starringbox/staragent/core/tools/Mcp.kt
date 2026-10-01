package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.ports.McpPort
import kotlinx.serialization.json.JsonObject

/** MCP Server 暴露给 Agent 的工具描述。 */
data class McpToolDescriptor(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** 将一个 MCP 工具包装成普通 Tool，复用现有 AgentLoop。 */
class McpTool(
    private val descriptor: McpToolDescriptor,
    private val port: McpPort,
) : Tool {
    override val definition = ToolDefinition(
        name = "mcp__${descriptor.serverId}__${descriptor.name}".replace(Regex("[^A-Za-z0-9_.-]"), "_"),
        description = "[MCP:${descriptor.serverId}] ${descriptor.description}",
        inputSchema = descriptor.inputSchema,
    )
    override val requiresConfirmation: Boolean = true

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val result = port.call(
            serverId = descriptor.serverId,
            toolName = descriptor.name,
            arguments = args,
            agentId = host.agentId,
        )
        return ToolResult("", result.content, result.isError, externalContext = result.externalContext)
    }
}

/** 运行时 MCP 工具注册表。没有安装/连接 MCP Server 时为空。 */
object McpRegistry {
    private val servers = linkedMapOf<String, List<Tool>>()

    fun install(port: McpPort, descriptors: List<McpToolDescriptor>) {
        descriptors.groupBy { it.serverId }.forEach { (serverId, group) ->
            servers[serverId] = group.map { McpTool(it, port) }
        }
    }

    fun remove(serverId: String) { servers.remove(serverId) }

    fun clear() { servers.clear() }

    fun registry(): ToolRegistry = ToolRegistry.of(*servers.values.flatten().toTypedArray())
}
