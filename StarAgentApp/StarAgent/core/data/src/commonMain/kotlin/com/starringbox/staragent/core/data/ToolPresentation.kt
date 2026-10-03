package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 移动产品的操作展示文案。核心运行时只处理调用和授权结果。
internal fun describeToolOperation(call: ToolCall, args: JsonObject): String =
    when (call.name) {
        "create_task" -> {
            val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            if (title != null) "创建托管任务：$title" else "创建托管任务"
        }
        "update_task" -> {
            val id = (args["id"] as? JsonPrimitive)?.content
            val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            when {
                title != null && id != null -> "将任务 $id 改名为「$title」"
                id != null -> "调整任务 $id 的执行时间"
                else -> "调整托管任务"
            }
        }
        "cancel_task" -> {
            val id = (args["id"] as? JsonPrimitive)?.content
            if (id != null) "取消托管任务 $id" else "取消托管任务"
        }
        "create_skill" -> {
            val name = (args["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val description = (args["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            "保存技能「${name ?: "未命名"}」${description?.let { "：$it" }.orEmpty()}"
        }
        "install_skill" -> {
            val path = (args["path"] as? JsonPrimitive)?.content
            if (path != null) "从 $path 安装技能" else "安装技能"
        }
        "device_use" -> {
            val op = (args["operation"] as? JsonPrimitive)?.content
            val target = (args["package_name"] as? JsonPrimitive)?.content ?: (args["app_name"] as? JsonPrimitive)?.content
            when (op) {
                "launch_app" -> if (target != null) "启动手机应用「$target」" else "启动手机应用"
                "click" -> "点击手机屏幕"
                "type" -> "在手机屏幕输入文字"
                "swipe" -> "在手机屏幕滑动"
                "back" -> "在手机上执行返回"
                else -> "执行手机操作"
            }
        }
        else -> readableLabelFor(call)
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
    "list_skills" -> "查看可用技能"
    "use_skill" -> "使用技能"
    "read_skill_file" -> "读取技能资料"
    "create_skill" -> "保存新技能"
    "set_skill_enabled" -> "设置技能状态"
    "install_skill" -> "安装技能"
    "remove_skill" -> "卸载技能"
    else -> call.name
}
