package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun schema(vararg required: String, properties: JsonObjectBuilder.() -> Unit) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject(properties))
    putJsonArray("required") { required.forEach(::add) }
}

private fun safePath(raw: String?): String? {
    val p = raw?.trim()?.replace('\\', '/') ?: return null
    if (p.isBlank() || p.startsWith('/') || p.contains("..") || p.contains('\u0000')) return null
    return p.trimStart('/')
}
private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.content

object ListFilesTool : Tool {
    override val definition = ToolDefinition("list_files", "列出当前智能体文件沙箱中的文件。", schema(properties = {
        putJsonObject("path") { put("type", "string"); put("description", "相对目录，可省略") }
    }))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val rawPath = args.str("path")
        if (rawPath != null && safePath(rawPath) == null) return ToolResult("", "路径不安全", true)
        val path = rawPath?.let(::safePath).orEmpty()
        val entries = host.files.list(host.agentId, path).joinToString("\n") { "- ${it.path}${if (it.directory) "/" else " (${it.size} bytes)"}" }
        return ToolResult("", entries.ifBlank { "目录为空。" })
    }
}
object ReadFileTool : Tool {
    override val definition = ToolDefinition("read_file", "读取当前智能体文件沙箱中的文本文件。", schema("path", properties = {
        putJsonObject("path") { put("type", "string") }
    }))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val path = safePath(args.str("path")) ?: return ToolResult("", "path 不安全或为空", true)
        val text = host.files.read(host.agentId, path) ?: return ToolResult("", "文件不存在：$path", true)
        return ToolResult("", text.take(20000))
    }
}
object WriteFileTool : Tool {
    override val definition = ToolDefinition("write_file", "写入当前智能体文件沙箱中的文本文件。", schema("path", "content", properties = {
        putJsonObject("path") { put("type", "string") }; putJsonObject("content") { put("type", "string") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val path = safePath(args.str("path")) ?: return ToolResult("", "path 不安全或为空", true)
        val result = host.files.write(host.agentId, path, args.str("content") ?: "")
        return ToolResult("", result.message, !result.ok)
    }
}
object DeleteFileTool : Tool {
    override val definition = ToolDefinition("delete_file", "删除当前智能体文件沙箱中的文件。", schema("path", properties = {
        putJsonObject("path") { put("type", "string") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val path = safePath(args.str("path")) ?: return ToolResult("", "path 不安全或为空", true)
        val result = host.files.delete(host.agentId, path)
        return ToolResult("", result.message, !result.ok)
    }
}
object InstallSkillTool : Tool {
    override val definition = ToolDefinition("install_skill", "从当前智能体文件沙箱中的 SKILL.md 安装一个本地 Skill。不会联网下载代码。", schema("path", properties = {
        putJsonObject("path") { put("type", "string"); put("description", "SKILL.md 的相对路径") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val path = safePath(args.str("path")) ?: return ToolResult("", "path 不安全或为空", true)
        val result = host.skills.installFromFile(host.agentId, path)
        return ToolResult("", result.message, !result.ok)
    }
}
object RemoveSkillTool : Tool {
    override val definition = ToolDefinition("remove_skill", "卸载一个已安装的 Skill。", schema("id", properties = {
        putJsonObject("id") { put("type", "string") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.str("id") ?: return ToolResult("", "缺少 id", true)
        val ok = host.skills.remove(id)
        return ToolResult("", if (ok) "Skill 已卸载：$id" else "找不到可卸载的 Skill：$id", !ok)
    }
}

object WebSearchTool : Tool {
    override val definition = ToolDefinition("web_search", "搜索公开网页并返回标题、链接和摘要。", schema("query", properties = {
        putJsonObject("query") { put("type", "string") }; putJsonObject("limit") { put("type", "integer") }
    }))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isBlank()) return ToolResult("", "query 不能为空", true)
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 10)
        val result = host.search.search(query, limit)
        if (!result.ok) return ToolResult("", result.error ?: "搜索失败", true)
        return ToolResult("", result.items.joinToString("\n") { "- ${it.title}\n  ${it.url}\n  ${it.snippet}" }.ifBlank { "没有找到结果。" }, externalContext = true)
    }
}
object RunCommandTool : Tool {
    override val definition = ToolDefinition("run_command", "在当前智能体的受限执行环境中运行命令。仅用于明确需要命令行的任务。", schema("command", properties = {
        putJsonObject("command") { put("type", "string") }; putJsonObject("working_directory") { put("type", "string") }; putJsonObject("timeout_seconds") { put("type", "integer") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val command = args.str("command")?.trim().orEmpty()
        if (command.isBlank()) return ToolResult("", "command 不能为空", true)
        val timeout = (((args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 30).coerceIn(1, 120) * 1000L)
        val result = host.commands.execute(host.agentId, command, args.str("working_directory"), timeout)
        val output = buildString { append("exitCode=${result.exitCode}\n"); if (result.timedOut) append("已超时\n"); append(result.stdout); if (result.stderr.isNotBlank()) append("\nstderr:\n").append(result.stderr) }
        return ToolResult("", output.take(20000), result.exitCode != 0 || result.timedOut)
    }
}
object ListSkillsTool : Tool {
    override val definition = ToolDefinition("list_skills", "列出可用 Skill 及其安装状态。", schema(properties = {}))
    override suspend fun execute(args: JsonObject, host: ToolHost) = ToolResult("", host.skills.list().joinToString("\n") { "- ${it.name} [${if (it.installed) "已启用" else "未启用"}]：${it.summary}（id=${it.id}）" }.ifBlank { "暂无 Skill。" })
}
object SetSkillInstalledTool : Tool {
    override val definition = ToolDefinition("set_skill_enabled", "安装或卸载一个已有 Skill。不会从网络下载未知代码。", schema("id", "installed", properties = {
        putJsonObject("id") { put("type", "string") }; putJsonObject("installed") { put("type", "boolean") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.str("id") ?: return ToolResult("", "缺少 id", true)
        val installed = (args["installed"] as? JsonPrimitive)?.booleanOrNull ?: return ToolResult("", "installed 必须是布尔值", true)
        val ok = host.skills.setInstalled(id, installed)
        return ToolResult("", if (ok) "Skill 状态已更新" else "找不到 Skill：$id", !ok)
    }
}

object McpListServersTool : Tool {
    override val definition = ToolDefinition("mcp_list_servers", "列出当前已连接的 MCP Server。", schema(properties = {}))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult = ToolResult("", host.mcp.list().joinToString("\n") { "- ${it.id}：已连接，${it.toolCount} 个工具" }.ifBlank { "当前没有已连接的 MCP Server。" })
}

object McpConnectTool : Tool {
    override val definition = ToolDefinition("mcp_connect", "连接一个 Desktop 本地 MCP Server。command 会作为外部进程启动，必须由用户明确授权。", schema("server_id", "command", properties = {
        putJsonObject("server_id") { put("type", "string") }
        putJsonObject("command") { put("type", "string") }
        putJsonObject("args") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.str("server_id")?.trim().orEmpty()
        val command = args.str("command")?.trim().orEmpty()
        if (id.isBlank() || command.isBlank()) return ToolResult("", "server_id 和 command 不能为空", true)
        val argsList = (args["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val result = host.mcp.connect(id, command, argsList)
        return ToolResult("", result.message, !result.ok)
    }
}

object McpDisconnectTool : Tool {
    override val definition = ToolDefinition("mcp_disconnect", "断开一个已连接的 MCP Server。", schema("server_id", properties = {
        putJsonObject("server_id") { put("type", "string") }
    }))
    override val requiresConfirmation = true
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.str("server_id")?.trim().orEmpty()
        if (id.isBlank()) return ToolResult("", "server_id 不能为空", true)
        val result = host.mcp.disconnect(id)
        return ToolResult("", result.message, !result.ok)
    }
}

object BrowserOpenTool : Tool {
    override val definition = ToolDefinition("browser_open", "在系统默认浏览器中打开一个 HTTPS 网页。", schema("url", properties = {
        putJsonObject("url") { put("type", "string") }
    }))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("https://")) return ToolResult("", "只支持 https 地址", true)
        val result = host.browser.open(url)
        return ToolResult("", result.content.ifBlank { result.error ?: "浏览器打开失败" }, !result.ok)
    }
}

object BrowserReadTool : Tool {
    override val definition = ToolDefinition("browser_read", "读取一个 HTTPS 网页的正文。适合在浏览器打开后继续获取页面内容。", schema("url", properties = {
        putJsonObject("url") { put("type", "string") }
    }))
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("https://")) return ToolResult("", "只支持 https 地址", true)
        val result = host.browser.read(url)
        return ToolResult("", result.content.ifBlank { result.error ?: "网页读取失败" }, !result.ok, externalContext = result.externalContext)
    }
}
