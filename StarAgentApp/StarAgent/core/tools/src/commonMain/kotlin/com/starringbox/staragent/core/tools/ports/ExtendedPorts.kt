package com.starringbox.staragent.core.tools.ports

/** Agent 可访问的受限文件沙箱。路径必须是相对路径。 */
interface FilePort {
    fun list(agentId: String, path: String): List<FileEntry>
    fun read(agentId: String, path: String): String?
    fun write(agentId: String, path: String, content: String): FileOperationResult
    fun readBytes(agentId: String, path: String): ByteArray? = null
    fun writeBytes(agentId: String, path: String, content: ByteArray): FileOperationResult =
        FileOperationResult(false, "当前文件端口不支持二进制文件")
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

/**
 * App 内关键功能对应的最小权限能力。
 *
 * 使用固定能力枚举，避免模型传入任意 Android 权限字符串。
 */
enum class PermissionCapability {
    Microphone,
    Notifications,
    Accessibility,
    ExactAlarm,
    Overlay,
}

enum class PermissionStatus {
    Granted,
    NotGranted,
    RequiresSettings,
    Unsupported,
}

data class PermissionRequestResult(
    val status: PermissionStatus,
    val message: String,
)

/** 权限检查与系统授权入口，由平台层实现。 */
interface PermissionPort {
    fun status(capability: PermissionCapability): PermissionStatus
    suspend fun request(capability: PermissionCapability): PermissionRequestResult
}

object NoopPermissionPort : PermissionPort {
    override fun status(capability: PermissionCapability) = PermissionStatus.Unsupported

    override suspend fun request(capability: PermissionCapability) =
        PermissionRequestResult(PermissionStatus.Unsupported, "当前平台没有接入权限申请能力")
}

/** 设备操作能力。句柄只在一次 inspect 返回的 screenId 内有效。 */
interface DevicePort {
    suspend fun inspect(scope: DeviceScope): DeviceScreenResult
    suspend fun click(screenId: String, elementId: String): DeviceActionResult
    suspend fun type(screenId: String, elementId: String?, text: String): DeviceActionResult
    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): DeviceActionResult
    suspend fun pressBack(): DeviceActionResult
    suspend fun launchApp(packageOrBundleId: String): DeviceActionResult
    suspend fun listApps(query: String? = null): DeviceAppsResult =
        DeviceAppsResult(false, emptyList(), "当前平台不支持查看已安装应用")

    /** Called by AgentLoop when the full turn ends, including cancellation. */
    fun finishOperationSession() = Unit
}

data class InstalledApp(val name: String, val packageName: String)

data class DeviceAppsResult(
    val ok: Boolean,
    val apps: List<InstalledApp> = emptyList(),
    val error: String? = null,
)

enum class DeviceScope { ActiveWindow, FullTree }

data class DeviceScreenResult(
    val ok: Boolean,
    val screenId: String = "",
    val packageName: String? = null,
    val elements: List<DeviceElement> = emptyList(),
    val error: String? = null,
)

data class DeviceElement(
    val id: String,
    val role: String,
    val label: String,
    val text: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class DeviceActionResult(val ok: Boolean, val message: String)

object NoopDevicePort : DevicePort {
    override suspend fun inspect(scope: DeviceScope) = DeviceScreenResult(false, error = "当前平台未安装设备控制能力")
    override suspend fun click(screenId: String, elementId: String) = DeviceActionResult(false, "当前平台未安装设备控制能力")
    override suspend fun type(screenId: String, elementId: String?, text: String) = DeviceActionResult(false, "当前平台未安装设备控制能力")
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long) = DeviceActionResult(false, "当前平台未安装设备控制能力")
    override suspend fun pressBack() = DeviceActionResult(false, "当前平台未安装设备控制能力")
    override suspend fun launchApp(packageOrBundleId: String) = DeviceActionResult(false, "当前平台未安装设备控制能力")
    override suspend fun listApps(query: String?) = DeviceAppsResult(false, emptyList(), "当前平台未安装设备控制能力")
}
