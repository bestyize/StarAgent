package com.starringbox.staragent.core.platform.mcp

import com.starringbox.staragent.core.tools.McpRegistry
import com.starringbox.staragent.core.tools.McpToolDescriptor
import com.starringbox.staragent.core.tools.ports.McpCallResult
import com.starringbox.staragent.core.tools.ports.McpPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/** Desktop MCP stdio transport using one JSON-RPC message per line. */
class DesktopMcpStdio(
    val serverId: String,
    private val command: String,
    private val args: List<String> = emptyList(),
    private val workingDirectory: File? = null,
    private val environment: Map<String, String> = emptyMap(),
) : McpPort, AutoCloseable {
    private val lock = Mutex()
    private var process: Process? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null
    private var nextId = 1L

    suspend fun discoverTools(): List<McpToolDescriptor> = lock.withLock {
        ensureStarted()
        request("tools/list", buildJsonObject { })["tools"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpToolDescriptor(
                serverId = serverId,
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = obj["inputSchema"]?.jsonObject ?: buildJsonObject { put("type", "object") },
            )
        }
    }

    override suspend fun call(
        serverId: String,
        toolName: String,
        arguments: JsonObject,
        agentId: String,
    ): McpCallResult = lock.withLock {
        if (serverId != this@DesktopMcpStdio.serverId) {
            return@withLock McpCallResult("MCP Server 不匹配：$serverId", isError = true, externalContext = false)
        }
        runCatching {
            ensureStarted()
            val result = request("tools/call", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
            val text = result["content"]?.jsonArray.orEmpty().joinToString("\n") { block ->
                block.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: block.toString()
            }
            McpCallResult(
                content = text.ifBlank { result.toString() },
                isError = result["isError"]?.jsonPrimitive?.content == "true",
            )
        }.getOrElse { McpCallResult("MCP 调用失败：${it.message ?: "未知错误"}", isError = true, externalContext = false) }
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val builder = ProcessBuilder(listOf(command) + args).redirectError(ProcessBuilder.Redirect.INHERIT)
        if (workingDirectory != null) builder.directory(workingDirectory)
        builder.environment().putAll(environment)
        val started = builder.start()
        process = started
        input = started.inputStream.bufferedReader()
        output = started.outputStream.bufferedWriter()

        request("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") {
                put("name", "StarAgent")
                put("version", "0.1")
            }
        })
        sendNotification("notifications/initialized", buildJsonObject { })
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        while (true) {
            val line = input?.readLine() ?: error("MCP Server 已退出")
            if (line.isBlank()) continue
            val message = Json.parseToJsonElement(line).jsonObject
            if (message["id"]?.jsonPrimitive?.long != id) continue
            message["error"]?.let { error("${it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it}") }
            return message["result"]?.jsonObject ?: buildJsonObject { }
        }
    }

    private fun sendNotification(method: String, params: JsonObject) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    private fun send(message: JsonObject) {
        output?.apply {
            write(message.toString())
            newLine()
            flush()
        } ?: error("MCP Server 尚未启动")
    }

    override fun close() {
        runCatching { output?.close() }
        runCatching { input?.close() }
        process?.destroy()
        process = null
        input = null
        output = null
    }
}

/** Desktop 端显式连接一个 MCP Server 并把发现到的工具安装到 Agent 工具注册表。 */
object DesktopMcp {
    private val connections = mutableMapOf<String, DesktopMcpStdio>()

    suspend fun connect(
        serverId: String,
        command: String,
        args: List<String> = emptyList(),
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
    ): List<McpToolDescriptor> {
        connections.remove(serverId)?.close()
        McpRegistry.remove(serverId)
        val connection = DesktopMcpStdio(serverId, command, args, workingDirectory, environment)
        val descriptors = runCatching { connection.discoverTools() }.getOrElse {
            connection.close()
            throw IllegalStateException("MCP Server $serverId 连接失败：${it.message}", it)
        }
        connections[serverId] = connection
        McpRegistry.install(connection, descriptors)
        return descriptors
    }

    fun listConnections(): List<Pair<String, Int>> = connections.keys.map { id ->
        id to McpRegistry.registry().definitions().count { it.name.startsWith("mcp__${id}__") }
    }

    fun disconnect(serverId: String) {
        connections.remove(serverId)?.close()
        McpRegistry.remove(serverId)
    }

    fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
        McpRegistry.clear()
    }
}
