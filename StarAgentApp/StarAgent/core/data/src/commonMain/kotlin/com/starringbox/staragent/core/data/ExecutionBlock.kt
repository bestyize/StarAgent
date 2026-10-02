package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult

/**
 * 把工具调用翻成用户看得懂的步骤。
 *
 * 界面上显示的必须是**在做什么**（"查资料"），不是工具的内部名字（"web_fetch"）。
 * 用户不关心我们内部把工具叫什么。
 *
 * 一期内置工具就那么几个，所以用一张表映射；表里没有的工具退回它的描述性名字，
 * 而不是空白——将来加工具忘了更新这张表，界面上也还是个能看的词。
 */
internal fun executionStepsFor(
    calls: List<ToolCall>,
    results: List<ToolResult>,
): List<ExecutionStep> {
    val doneIds = results.map { it.callId }.toSet()
    return calls.map { call ->
        ExecutionStep(
            label = readableLabelFor(call),
            done = call.id in doneIds,
        )
    }
}

internal fun readableLabelFor(call: ToolCall): String = when (call.name) {
    "recall_memory" -> "回忆关于你的事"
    "remember" -> "记住这件事"
    "create_task" -> "创建托管任务"
    "list_tasks" -> "查看任务"
    "update_task" -> "调整任务"
    "cancel_task" -> "取消任务"
    "web_fetch" -> "读取网页"
    "request_user_input" -> "向你提问"
    "list_apps" -> "查看已安装应用"
    "device_inspect" -> "查看手机当前界面"
    "device_use" -> "操作手机应用"
    "request_permission" -> "申请系统权限"
    else -> call.name
}
