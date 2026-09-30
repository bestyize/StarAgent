package com.starringbox.staragent.core.data.ports

import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.tools.ports.TaskPort
import com.starringbox.staragent.core.tools.ports.TaskSummary

/**
 * 任务端口的实现。
 *
 * 每个方法都先核对 **agentId**：模型不该能通过工具改到别的智能体的任务。
 * 这是没有权限框架时的一道代码级边界——写在实现里，不靠框架。
 */
class AppTaskPort(private val state: AppState) : TaskPort {

    override suspend fun create(
        agentId: String,
        title: String,
        recurrence: Recurrence,
        scheduleLabel: String,
        note: String?,
    ): String = state.createTaskFromTool(agentId, title, recurrence, scheduleLabel, note)

    override suspend fun list(agentId: String): List<TaskSummary> = state.taskSummariesFor(agentId)

    override suspend fun update(
        agentId: String,
        id: String,
        title: String?,
        scheduleLabel: String?,
    ): Boolean = state.updateTaskFromTool(agentId, id, title, scheduleLabel)

    override suspend fun cancel(agentId: String, id: String): Boolean =
        state.cancelTaskFromTool(agentId, id)
}
