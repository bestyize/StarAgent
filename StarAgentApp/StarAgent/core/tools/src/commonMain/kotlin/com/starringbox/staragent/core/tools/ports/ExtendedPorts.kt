package com.starringbox.staragent.core.tools.ports

/** Agent 可访问的受限文件沙箱。路径必须是相对路径。 */
interface FilePort {
    fun list(agentId: String, path: String): List<FileEntry>
    fun read(agentId: String, path: String): String?
    fun write(agentId: String, path: String, content: String): FileOperationResult
    fun delete(agentId: String, path: String): FileOperationResult
}

data class FileEntry(val path: String, val size: Long, val directory: Boolean)
data class FileOperationResult(val ok: Boolean, val message: String)

/** 受限命令执行。实现可以在平台侧完全禁用。 */
interface CommandPort {
    suspend fun execute(agentId: String, command: String, workingDirectory: String?, timeoutMillis: Long): CommandResult
}
data class CommandResult(val exitCode: Int?, val stdout: String, val stderr: String, val timedOut: Boolean = false)

interface SearchPort {
    suspend fun search(query: String, limit: Int): SearchResult
}
data class SearchResult(val ok: Boolean, val items: List<SearchItem>, val error: String? = null)
data class SearchItem(val title: String, val url: String, val snippet: String)

interface SkillPort {
    fun list(): List<SkillSummary>
    fun setInstalled(id: String, installed: Boolean): Boolean
    fun installFromFile(agentId: String, path: String): SkillInstallResult
    fun remove(id: String): Boolean
}
data class SkillInstallResult(val ok: Boolean, val id: String? = null, val message: String)

data class SkillSummary(val id: String, val name: String, val summary: String, val installed: Boolean)

object NoopFilePort : FilePort {
    override fun list(agentId: String, path: String) = emptyList<FileEntry>()
    override fun read(agentId: String, path: String): String? = null
    override fun write(agentId: String, path: String, content: String) = FileOperationResult(false, "文件能力未安装")
    override fun delete(agentId: String, path: String) = FileOperationResult(false, "文件能力未安装")
}
object NoopCommandPort : CommandPort {
    override suspend fun execute(agentId: String, command: String, workingDirectory: String?, timeoutMillis: Long) =
        CommandResult(null, "", "命令执行能力未安装")
}
object NoopSearchPort : SearchPort {
    override suspend fun search(query: String, limit: Int) = SearchResult(false, emptyList(), "搜索能力未配置")
}
object NoopSkillPort : SkillPort {
    override fun list() = emptyList<SkillSummary>()
    override fun setInstalled(id: String, installed: Boolean) = false
    override fun installFromFile(agentId: String, path: String) = SkillInstallResult(false, message = "Skill 能力未安装")
    override fun remove(id: String) = false
}

/** MCP 调用结果。Transport 和配置由平台/数据层实现。 */
interface McpPort {
    suspend fun call(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject, agentId: String): McpCallResult
}
data class McpCallResult(val content: String, val isError: Boolean = false, val externalContext: Boolean = true)
object NoopMcpPort : McpPort {
    override suspend fun call(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject, agentId: String) =
        McpCallResult("MCP Server 未连接：$serverId", isError = true, externalContext = false)
}

/** 浏览器能力：打开外部 URL，以及读取当前可访问的网页正文。 */
interface BrowserPort {
    suspend fun open(url: String): BrowserResult
    suspend fun read(url: String): BrowserResult
}
data class BrowserResult(val ok: Boolean, val content: String = "", val error: String? = null, val externalContext: Boolean = false)
object NoopBrowserPort : BrowserPort {
    override suspend fun open(url: String) = BrowserResult(false, error = "浏览器能力未安装")
    override suspend fun read(url: String) = BrowserResult(false, error = "浏览器能力未安装")
}

/** MCP Server 生命周期管理能力。 */
interface McpManagerPort {
    fun list(): List<McpServerSummary>
    suspend fun connect(serverId: String, command: String, args: List<String>): McpManagerResult
    fun disconnect(serverId: String): McpManagerResult
}
data class McpServerSummary(val id: String, val connected: Boolean, val toolCount: Int)
data class McpManagerResult(val ok: Boolean, val message: String, val toolCount: Int = 0)
object NoopMcpManagerPort : McpManagerPort {
    override fun list() = emptyList<McpServerSummary>()
    override suspend fun connect(serverId: String, command: String, args: List<String>) = McpManagerResult(false, "当前平台不支持启动本地 MCP Server")
    override fun disconnect(serverId: String) = McpManagerResult(false, "当前平台不支持 MCP Server 管理")
}
