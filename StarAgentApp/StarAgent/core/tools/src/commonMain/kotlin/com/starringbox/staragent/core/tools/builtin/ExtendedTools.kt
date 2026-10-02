package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.Artifact
import com.starringbox.staragent.core.model.ArtifactKind
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ports.DeviceScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

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

/**
 * 把一次回复沉淀成可预览的本地文件。文件始终写入当前智能体的沙箱，
 * 路径由系统生成，不接受模型提供的目录。
 */
object CreateArtifactTool : Tool {
    override val definition = ToolDefinition(
        "create_artifact",
        "用户要求交付日报、报告、计划、清单、图片或文件时调用。日报和报告优先生成 HTML；可生成 HTML、SVG、Markdown、文本、JSON、CSV，以及 PNG/JPEG/WebP（content 使用 base64 或 data URL）。没有图片生成端点时，可把插画或示意图交付为 SVG；也可用 HTTPS source_url 引用已有图像。生成的文件会保存并在回复中显示可点击预览卡片。",
        schema("title", "format", properties = {
            putJsonObject("title") { put("type", "string"); put("description", "产物标题") }
            putJsonObject("summary") { put("type", "string"); put("description", "一行摘要") }
            putJsonObject("kind") {
                put("type", "string")
                put("enum", buildJsonArray { add("digest"); add("report"); add("checklist"); add("draft"); add("plan"); add("image"); add("file") })
            }
            putJsonObject("format") {
                put("type", "string")
                put("enum", buildJsonArray { add("html"); add("svg"); add("markdown"); add("text"); add("json"); add("csv"); add("png"); add("jpeg"); add("webp"); add("image_url") })
            }
            putJsonObject("file_name") { put("type", "string"); put("description", "可选文件名，不含目录") }
            putJsonObject("content") { put("type", "string"); put("description", "文件正文；PNG/JPEG/WebP 填裸 base64 或 data URL") }
            putJsonObject("source_url") { put("type", "string"); put("description", "image_url 格式使用，仅接受 HTTPS") }
        }),
    )

    override val requiresConfirmation: Boolean = false

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val title = args.str("title")?.trim()?.take(160).orEmpty()
        if (title.isBlank()) return ToolResult("", "title 不能为空", true)
        val format = args.str("format")?.trim()?.lowercase().orEmpty()
        val extension = when (format) {
            "html" -> "html"
            "svg" -> "svg"
            "markdown" -> "md"
            "text" -> "txt"
            "json" -> "json"
            "csv" -> "csv"
            "png" -> "png"
            "jpeg" -> "jpg"
            "webp" -> "webp"
            "image_url" -> ""
            else -> return ToolResult("", "不支持的产物格式：$format", true)
        }

        val requestedName = args.str("file_name")?.trim().orEmpty()
        if (requestedName.isNotEmpty() &&
            (!requestedName.matches(Regex("[A-Za-z0-9._-]{1,100}")) || requestedName == "." || requestedName == "..")
        ) {
            return ToolResult("", "file_name 只能包含英文字母、数字、点、下划线和短横线", true)
        }
        val id = "artifact-" + Random.nextLong().toString().replace("-", "")
        val defaultKind = when {
            format == "image_url" || format in setOf("svg", "png", "jpeg", "webp") -> ArtifactKind.Image
            format == "html" -> ArtifactKind.Report
            else -> ArtifactKind.File
        }
        val kind = when (args.str("kind")?.lowercase()) {
            null, "" -> defaultKind
            "digest" -> ArtifactKind.Digest
            "report" -> ArtifactKind.Report
            "checklist" -> ArtifactKind.Checklist
            "draft" -> ArtifactKind.Draft
            "plan" -> ArtifactKind.Plan
            "image" -> ArtifactKind.Image
            "file" -> ArtifactKind.File
            else -> return ToolResult("", "kind 不受支持", true)
        }
        val summary = args.str("summary")?.trim()?.take(240).orEmpty()
        var filePath: String? = null
        var fileName: String? = null
        var sourceUrl: String? = null
        val mimeType = when (format) {
            "html" -> "text/html"
            "svg" -> "image/svg+xml"
            "markdown" -> "text/markdown"
            "text" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "image_url" -> "image/*"
            else -> error("unreachable")
        }

        if (format == "image_url") {
            sourceUrl = args.str("source_url")?.trim()?.takeIf { url ->
                url.length > 8 && url.startsWith("https://") && url.none { it.isWhitespace() || it == '"' || it == '<' || it == '>' || it == '\\' }
            } ?: return ToolResult("", "image_url 必须提供安全的 HTTPS source_url", true)
        } else {
            val content = args.str("content") ?: return ToolResult("", "缺少 content", true)
            val binary = format in setOf("png", "jpeg", "webp")
            if (content.length > if (binary) 8_000_000 else 1_500_000) {
                return ToolResult("", "文件内容太大，请拆分或缩小后再生成", true)
            }
            fileName = when {
                requestedName.isBlank() -> "output.$extension"
                requestedName.substringAfterLast('.', missingDelimiterValue = "").equals(extension, ignoreCase = true) -> requestedName
                else -> "$requestedName.$extension"
            }
            filePath = "artifacts/$id/$fileName"
            val write = if (binary) {
                val payload = content.substringAfter("base64,", content).trim()
                val bytes = runCatching { Base64.Default.decode(payload) }.getOrElse {
                    return ToolResult("", "图片 content 必须是有效的 base64 或 data URL", true)
                }
                host.files.writeBytes(host.agentId, filePath, bytes)
            } else {
                host.files.write(host.agentId, filePath, content)
            }
            if (!write.ok) return ToolResult("", write.message, true)
        }

        val artifact = Artifact(
            id = id,
            title = title,
            kind = kind,
            summary = summary,
            mimeType = mimeType,
            fileName = fileName,
            agentId = host.agentId,
            filePath = filePath,
            sourceUrl = sourceUrl,
        )
        return ToolResult(
            callId = "",
            content = "已生成「$title」${fileName?.let { "（$it）" }.orEmpty()}。预览链接：artifact://$id",
            artifact = artifact,
        )
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

object DeviceInspectTool : Tool {
    override val definition = ToolDefinition("device_inspect", "读取当前 Android 前台窗口的可访问控件树，返回短期有效的元素句柄。用户需要先启用 StarAgent 无障碍服务。", schema(properties = {
        putJsonObject("scope") {
            put("type", "string")
            put("enum", buildJsonArray { add("active_window"); add("full_tree") })
        }
    }))

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val scope = when (args.str("scope")) {
            "full_tree" -> DeviceScope.FullTree
            else -> DeviceScope.ActiveWindow
        }
        val result = host.device.inspect(scope)
        if (!result.ok) {
            val err = result.error ?: "无法读取设备界面"
            val hint = if (err.contains("无障碍")) {
                "\n提示：请先调用 request_permission(capability='accessibility') 协助用户前往系统设置开启无障碍权限；用户授权返回后即可继续读取界面。"
            } else ""
            return ToolResult("", err + hint, true)
        }
        val text = buildString {
            append("screen_id=").append(result.screenId).append("\n")
            append("package=").append(result.packageName ?: "unknown").append("\n")
            result.elements.forEach { element ->
                append("- id=").append(element.id)
                    .append(" role=").append(element.role)
                    .append(" label=").append(element.label)
                    .append(" text=").append(element.text)
                    .append(" clickable=").append(element.clickable)
                    .append(" editable=").append(element.editable)
                    .append(" enabled=").append(element.enabled)
                    .append(" bounds=").append("[").append(element.left).append(",").append(element.top)
                    .append(",").append(element.right).append(",").append(element.bottom).append("]\n")
            }
        }
        return ToolResult("", text.take(30000))
    }
}

object DeviceUseTool : Tool {
    override val definition = ToolDefinition("device_use", "在用户已授权的设备上执行一个受控操作。支持 launch_app (启动应用，支持中文名如「网易云音乐」或包名)、list_apps (列出已安装应用)、click (点击)、type (输入)、swipe (滑动)、back (返回)。跨 App 操作前先检查悬浮窗权限；未开启无障碍时仅完成应用启动。", schema("operation", properties = {
        putJsonObject("operation") {
            put("type", "string")
            put("description", "操作类型：launch_app、list_apps、click、type、swipe、back")
            put("enum", buildJsonArray { add("launch_app"); add("list_apps"); add("click"); add("type"); add("swipe"); add("back") })
        }
        putJsonObject("package_name") {
            put("type", "string")
            put("description", "要启动的应用名称或包名（如「微信」、「网易云音乐」或「com.tencent.mm」），用于 launch_app")
        }
        putJsonObject("screen_id") { put("type", "string") }
        putJsonObject("element_id") { put("type", "string") }
        putJsonObject("text") { put("type", "string") }
        putJsonObject("from_x") { put("type", "number") }
        putJsonObject("from_y") { put("type", "number") }
        putJsonObject("to_x") { put("type", "number") }
        putJsonObject("to_y") { put("type", "number") }
        putJsonObject("duration_ms") { put("type", "integer") }
    }))
    override val requiresConfirmation = true

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val operation = args.str("operation") ?: return ToolResult("", "缺少 operation", true)
        val result = when (operation) {
            "launch_app" -> {
                val target = args.str("package_name") ?: args.str("app_name")
                    ?: return ToolResult("", "launch_app 需要 package_name 或 app_name", true)
                val res = host.device.launchApp(target)
                return ToolResult("", res.message, !res.ok)
            }
            "list_apps" -> {
                val query = args.str("package_name") ?: args.str("query") ?: args.str("name")
                val res = host.device.listApps(query)
                if (!res.ok) return ToolResult("", res.error ?: "无法获取已安装应用", true)
                val apps = res.apps
                val text = if (apps.isEmpty()) {
                    if (query != null) "手机上未找到名称或包名包含「$query」的应用。" else "手机上未找到已安装的可启动应用。"
                } else {
                    buildString {
                        append("手机上已安装的应用程序（共 ${apps.size} 个）：\n")
                        apps.take(40).forEach { app ->
                            append("- ${app.name} (${app.packageName})\n")
                        }
                        if (apps.size > 40) {
                            append("...其余 ${apps.size - 40} 个应用已省略，可通过 query 关键词过滤。\n")
                        }
                    }
                }
                return ToolResult("", text)
            }
            "click" -> {
                val screen = args.str("screen_id") ?: return ToolResult("", "click 需要 screen_id", true)
                val element = args.str("element_id") ?: return ToolResult("", "click 需要 element_id", true)
                host.device.click(screen, element)
            }
            "type" -> {
                val screen = args.str("screen_id") ?: return ToolResult("", "type 需要 screen_id", true)
                val element = args.str("element_id")
                host.device.type(screen, element, args.str("text") ?: "")
            }
            "swipe" -> host.device.swipe(
                number(args, "from_x"), number(args, "from_y"), number(args, "to_x"), number(args, "to_y"),
                (((args["duration_ms"] as? JsonPrimitive)?.intOrNull ?: 400).coerceIn(80, 2_000)).toLong(),
            )
            "back" -> host.device.pressBack()
            else -> return ToolResult("", "不支持的 device_use operation：$operation", true)
        }
        val failure = !result.ok
        val msg = if (failure && result.message.contains("无障碍")) {
            result.message + "\n提示：请调用 request_permission(capability='accessibility') 协助用户前往系统设置开启无障碍权限；用户授权返回后即可继续执行手机操作。"
        } else {
            result.message
        }
        return ToolResult("", msg, failure)
    }

    private fun number(args: JsonObject, name: String): Float =
        (args[name] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
}

/**
 * 查询手机已安装应用列表。
 */
object ListAppsTool : Tool {
    override val definition = ToolDefinition(
        "list_apps",
        "查询手机上已安装的应用程序列表（包含中文显示名称与包名）。支持传入 query 关键词进行模糊过滤（如「音乐」、「微信」）。在想要启动某个 App 但不知道具体应用名或包名时调用。",
        schema(properties = {
            putJsonObject("query") {
                put("type", "string")
                put("description", "应用名称或包名的搜索关键词，可选，留空则返回所有已安装应用")
            }
        })
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val query = args.str("query") ?: args.str("name")
        val res = host.device.listApps(query)
        if (!res.ok) return ToolResult("", res.error ?: "无法获取已安装应用", true)
        val apps = res.apps
        val text = if (apps.isEmpty()) {
            if (query != null) "手机上未找到名称或包名包含「$query」的应用。" else "手机上未找到已安装的可启动应用。"
        } else {
            buildString {
                append("手机上已安装的应用程序（共 ${apps.size} 个）：\n")
                apps.take(40).forEach { app ->
                    append("- ${app.name} (${app.packageName})\n")
                }
                if (apps.size > 40) {
                    append("...其余 ${apps.size - 40} 个应用已省略，可通过 query 关键词过滤。\n")
                }
            }
        }
        return ToolResult("", text)
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
