package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.tools.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolTest {
    @Test
    fun wrapsDescriptorAndForwardsCall() = runTest {
        var received = ""
        val port = object : McpPort {
            override suspend fun call(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject, agentId: String): McpCallResult {
                received = "$serverId/$toolName/$agentId/${arguments["x"]}"
                return McpCallResult("ok")
            }
        }
        val tool = McpTool(
            McpToolDescriptor("demo", "echo", "echo", buildJsonObject { put("type", "object") }),
            port,
        )
        val result = tool.execute(
            buildJsonObject { put("x", "1") },
            ToolHost(NoopMemoryPort, NoopTaskPort, NoopWebPort, executionPolicy = ExecutionPolicy.Auto, agentId = "a", conversationKey = "a"),
        )
        assertEquals("ok", result.content)
        assertTrue(received.startsWith("demo/echo/a/"))
        assertTrue(tool.definition.name.startsWith("mcp__demo__echo"))
    }
}
