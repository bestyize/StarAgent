package com.starringbox.staragent.core.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolTest {

    @Test
    fun toolResultDefaultsAreConservative() {
        val result = ToolResult(callId = "c1", content = "done")
        // 默认不是错误、也不含外部内容——两条默认值都取"安全"的那一侧
        assertFalse(result.isError)
        assertFalse(result.externalContext)
        assertEquals(null, result.logText)
    }

    @Test
    fun toolCallKeepsRawArgumentsAsString() {
        // arguments 故意是 String：模型不总生成合法 JSON，
        // 解析推迟到工具执行前，那里才能把失败变成一条给模型看的错误
        val call = ToolCall(id = "c1", name = "remember", arguments = "{not json")
        assertEquals("{not json", call.arguments)
    }

    @Test
    fun toolDefinitionCarriesJsonSchema() {
        val definition = ToolDefinition(
            name = "remember",
            description = "存一条记忆",
            inputSchema = buildJsonObject { put("type", "object") },
        )
        assertEquals("object", definition.inputSchema["type"].toString().trim('"'))
    }
}
