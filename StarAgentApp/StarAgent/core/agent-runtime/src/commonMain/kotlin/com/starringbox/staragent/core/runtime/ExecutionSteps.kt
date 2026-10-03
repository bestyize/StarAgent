package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.model.ExecutionStep
import com.starringbox.staragent.core.model.ToolCall
import com.starringbox.staragent.core.model.ToolResult

/**
 * 把工具调用计算为用户可读的执行步骤。
 */
fun executionStepsFor(
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

fun readableLabelFor(call: ToolCall): String = when (call.name) {
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
    "list_skills" -> "查看可用技能"
    "use_skill" -> "使用技能"
    "read_skill_file" -> "读取技能资料"
    "create_skill" -> "保存新技能"
    "set_skill_enabled" -> "设置技能状态"
    "install_skill" -> "安装技能"
    "remove_skill" -> "卸载技能"
    else -> call.name
}
