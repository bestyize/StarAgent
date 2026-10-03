package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.NoopUserInputPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.UserInputPort
import com.starringbox.staragent.core.tools.ports.WebPort
import com.starringbox.staragent.core.tools.ports.FilePort
import com.starringbox.staragent.core.tools.ports.CommandPort
import com.starringbox.staragent.core.tools.ports.SearchPort
import com.starringbox.staragent.core.tools.ports.SkillPort
import com.starringbox.staragent.core.tools.ports.BrowserPort
import com.starringbox.staragent.core.tools.ports.NoopBrowserPort
import com.starringbox.staragent.core.tools.ports.McpManagerPort
import com.starringbox.staragent.core.tools.ports.DevicePort
import com.starringbox.staragent.core.tools.ports.PermissionPort
import com.starringbox.staragent.core.tools.ports.NoopPermissionPort
import com.starringbox.staragent.core.tools.ports.NoopDevicePort
import com.starringbox.staragent.core.tools.ports.NoopMcpManagerPort
import com.starringbox.staragent.core.tools.ports.NoopFilePort
import com.starringbox.staragent.core.tools.ports.NoopCommandPort
import com.starringbox.staragent.core.tools.ports.NoopSearchPort
import com.starringbox.staragent.core.tools.ports.NoopSkillPort

/**
 * 一次工具调用的现场。
 *
 * 工具可以是无状态单例；调用身份与执行现场在这里按轮次提供。
 * 资源释放由宿主注入，运行时不直接调用具体设备端口的收尾方法。
 */
class ToolHost(
    val memory: MemoryPort,
    val tasks: TaskPort,
    val web: WebPort,
    val userInput: UserInputPort = NoopUserInputPort,
    val files: FilePort = NoopFilePort,
    val commands: CommandPort = NoopCommandPort,
    val search: SearchPort = NoopSearchPort,
    val skills: SkillPort = NoopSkillPort,
    val browser: BrowserPort = NoopBrowserPort,
    val mcp: McpManagerPort = NoopMcpManagerPort,
    val device: DevicePort = NoopDevicePort,
    val permissions: PermissionPort = NoopPermissionPort,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy.Auto,
    val agentId: String,
    val conversationKey: String,
    val onTurnFinished: suspend () -> Unit = {},
)
