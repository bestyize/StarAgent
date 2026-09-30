package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private fun toolNamed(name: String) = object : Tool {
    override val definition = ToolDefinition(
        name = name,
        description = "测试用",
        inputSchema = buildJsonObject { put("type", "object") },
    )
    override suspend fun execute(args: JsonObject, host: ToolHost) =
        ToolResult(callId = "", content = name)
}

class ToolRegistryTest {

    @Test
    fun findsToolByRegisteredName() {
        val registry = ToolRegistry.of(toolNamed("remember"), toolNamed("create_task"))
        assertNotNull(registry.find("remember"))
        assertNotNull(registry.find("create_task"))
    }

    @Test
    fun returnsNullForUnknownTool() {
        assertNull(ToolRegistry.of(toolNamed("remember")).find("nope"))
    }

    @Test
    fun emptyRegistryHasNoTools() {
        assertEquals(emptyList(), ToolRegistry.Empty.definitions())
        assertNull(ToolRegistry.Empty.find("anything"))
    }

    @Test
    fun mergeUnionsBothSides() {
        val merged = ToolRegistry.of(toolNamed("a")) + ToolRegistry.of(toolNamed("b"))
        assertEquals(listOf("a", "b"), merged.definitions().map { it.name })
    }

    @Test
    fun definitionsPreserveRegistrationOrder() {
        // 顺序影响模型看到的清单，必须是稳定的
        val registry = ToolRegistry.of(toolNamed("z"), toolNamed("a"), toolNamed("m"))
        assertEquals(listOf("z", "a", "m"), registry.definitions().map { it.name })
    }
}
