package com.starringbox.staragent.core.tools

import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.tools.ports.MemoryPort
import com.starringbox.staragent.core.tools.ports.NoopUserInputPort
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.UserInputPort
import com.starringbox.staragent.core.tools.ports.WebPort

/**
 * 一次工具调用的现场。
 *
 * 工具**不能是单例**——记忆和任务都是按智能体隔离的，工具得知道是谁在调。
 * 端口本身是无状态的（方法都带 agentId），所以"是谁"放在这里而不是端口里。
 */
class ToolHost(
    val memory: MemoryPort,
    val tasks: TaskPort,
    val web: WebPort,
    val userInput: UserInputPort = NoopUserInputPort,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy.Auto,
    val agentId: String,
    val conversationKey: String,
)
