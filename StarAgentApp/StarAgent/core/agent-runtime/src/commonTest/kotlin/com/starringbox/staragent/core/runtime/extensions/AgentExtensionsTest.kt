package com.starringbox.staragent.core.runtime.extensions

import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentExtensionsTest {
    private val agent = Agent("test", "test", "", "", "test")

    @Test
    fun refusesDuplicateOrBlankExtensionIds() {
        assertFailsWith<IllegalArgumentException> { AgentExtensions(listOf(extension("same", "a"), extension("same", "b"))) }
        assertFailsWith<IllegalArgumentException> { AgentExtensions(listOf(extension(" ", "a"))) }
    }

    @Test
    fun refusesAmbiguousToolsAcrossExtensions() {
        val extensions = AgentExtensions(listOf(extension("one", "same"), extension("two", "same")))
        assertFailsWith<IllegalArgumentException> { extensions.registryFor(agent) }
    }

    @Test
    fun keepsAssemblyOrderAndCopiesTheInputList() {
        val source = mutableListOf(extension("first", "b"), extension("second", "a"))
        val extensions = AgentExtensions(source)
        source.clear()
        (extensions.installed as MutableList<AgentExtension>).clear()
        assertEquals(listOf("b", "a"), extensions.registryFor(agent).definitions().map { it.name })
        assertEquals(listOf("first", "second"), extensions.promptSections(agent, true))
        assertEquals(emptyList(), extensions.promptSections(agent, false))
    }

    private fun extension(id: String, name: String) = object : AgentExtension {
        override val id = id
        override fun tools(agent: Agent) = listOf(object : Tool {
            override val definition = ToolDefinition(name, name, JsonObject(emptyMap()))
            override suspend fun execute(args: JsonObject, host: ToolHost) = ToolResult("", name)
        })
        override fun contributePrompt(agent: Agent, toolsAvailable: Boolean) = if (toolsAvailable) "  $id  " else " "
    }
}
