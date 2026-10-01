package com.starringbox.staragent.core.platform

import com.starringbox.staragent.core.platform.mcp.DesktopMcp
import com.starringbox.staragent.core.tools.ports.*

private object DesktopMcpManager : McpManagerPort {
    override fun list(): List<McpServerSummary> = DesktopMcp.listConnections()
        .map { McpServerSummary(it.first, true, it.second) }

    override suspend fun connect(serverId: String, command: String, args: List<String>): McpManagerResult =
        runCatching {
            val tools = DesktopMcp.connect(serverId, command, args)
            McpManagerResult(true, "已连接 MCP Server：$serverId", tools.size)
        }.getOrElse { McpManagerResult(false, it.message ?: "MCP 连接失败") }

    override fun disconnect(serverId: String): McpManagerResult {
        DesktopMcp.disconnect(serverId)
        return McpManagerResult(true, "已断开 MCP Server：$serverId")
    }
}

actual fun platformMcpManager(): McpManagerPort = DesktopMcpManager
