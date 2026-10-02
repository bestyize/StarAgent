package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.tools.ToolRegistry

/**
 * 一期内置的全部工具。
 *
 * 注册顺序是稳定的，模型看到的清单顺序就是它——顺序会影响模型的选择，
 * 所以别随手插在中间。
 */
object BuiltinTools {
    val all: ToolRegistry = ToolRegistry.of(
        RecallMemoryTool,
        RememberTool,
        CreateTaskTool,
        ListTasksTool,
        UpdateTaskTool,
        CancelTaskTool,
        WebFetchTool,
        RequestUserInputTool,
        ListFilesTool, ReadFileTool, WriteFileTool, DeleteFileTool,
        WebSearchTool, RunCommandTool, ListSkillsTool, SetSkillInstalledTool, InstallSkillTool, RemoveSkillTool, BrowserOpenTool, BrowserReadTool, McpListServersTool, McpConnectTool, McpDisconnectTool, DeviceInspectTool, DeviceUseTool, RequestPermissionTool,
    )

    /**
     * 不含 [CreateTaskTool] 的工具列表。
     *
     * 供 `canScheduleTasks = false` 的智能体（如陪聊、学习类）使用，
     * 阻止模型主动为用户发起新的托管任务。
     */
    val withoutTaskCreation: ToolRegistry = ToolRegistry.of(
        RecallMemoryTool,
        RememberTool,
        ListTasksTool,
        UpdateTaskTool,
        CancelTaskTool,
        WebFetchTool,
        RequestUserInputTool,
        ListFilesTool, ReadFileTool, WriteFileTool, DeleteFileTool,
        WebSearchTool, RunCommandTool, ListSkillsTool, SetSkillInstalledTool, InstallSkillTool, RemoveSkillTool, BrowserOpenTool, BrowserReadTool, McpListServersTool, McpConnectTool, McpDisconnectTool, DeviceInspectTool, DeviceUseTool, RequestPermissionTool,
    )
}
