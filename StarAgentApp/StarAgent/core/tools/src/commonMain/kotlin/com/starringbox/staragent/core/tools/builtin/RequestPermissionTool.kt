package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import com.starringbox.staragent.core.tools.ports.PermissionCapability
import com.starringbox.staragent.core.tools.ports.PermissionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun permissionToolSchema(
    properties: JsonObjectBuilder.() -> Unit,
) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject(properties))
    putJsonArray("required") { add("capability") }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.content

/**
 * 只申请 StarAgent 明确支持的权限能力，不接受任意系统权限名。
 * Android 系统弹窗或设置页始终要求用户本人作出选择。
 */
object RequestPermissionTool : Tool {
    override val definition = ToolDefinition(
        "request_permission",
        "为当前明确要执行的功能检查并申请最小 Android 权限。capability 可选 microphone、notifications、accessibility、exact_alarm、overlay、battery_optimization。运行时权限会显示系统弹窗；accessibility、exact_alarm、overlay 和 battery_optimization 会在征得用户同意后打开对应系统设置页，由用户亲自确认。只在用户当前确实要使用该功能时调用，不要在后台任务中申请。",
        permissionToolSchema {
            putJsonObject("capability") {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("microphone")
                    add("notifications")
                    add("accessibility")
                    add("exact_alarm")
                    add("overlay")
                    add("battery_optimization")
                })
                put("description", "要申请的 StarAgent 功能权限")
            }
        },
    )

    // 此工具涉及用户思考、点击确认和系统设置页授权，给予 5 分钟超时
    override val timeoutMillis: Long = 5 * 60 * 1000L

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val capability = when (args.string("capability")) {
            "microphone" -> PermissionCapability.Microphone
            "notifications" -> PermissionCapability.Notifications
            "accessibility" -> PermissionCapability.Accessibility
            "exact_alarm" -> PermissionCapability.ExactAlarm
            "overlay" -> PermissionCapability.Overlay
            "battery_optimization" -> PermissionCapability.BatteryOptimization
            else -> return ToolResult("", "capability 缺失或不受支持。", true)
        }

        val currentStatus = host.permissions.status(capability)
        if (currentStatus == PermissionStatus.Granted) {
            return ToolResult("", "所需权限「${capabilityLabel(capability)}」已经开启。")
        }
        if (currentStatus == PermissionStatus.Unsupported) {
            return ToolResult("", "当前平台不支持申请「${capabilityLabel(capability)}」权限。", true)
        }

        // 若需要前往系统设置（无障碍、悬浮窗、闹钟、电池优化，或被永久拒绝的通知等），先在界面向用户友好询问确认
        val requiresSettings = currentStatus == PermissionStatus.RequiresSettings ||
            capability == PermissionCapability.Accessibility ||
            capability == PermissionCapability.Overlay ||
            capability == PermissionCapability.ExactAlarm ||
            capability == PermissionCapability.BatteryOptimization

        if (requiresSettings && host.userInput != com.starringbox.staragent.core.tools.ports.NoopUserInputPort) {
            val question = promptForCapability(capability)
            val userChoice = host.userInput.requestInput(
                agentId = host.agentId,
                question = question,
                options = listOf("前往开启", "暂不开启"),
                placeholder = null,
            )
            val trimmed = userChoice.trim()
            val declined = trimmed.contains("不") || trimmed.contains("拒绝") || trimmed.contains("取消")
            val agreed = (trimmed.contains("前往") || trimmed.contains("允许") || trimmed.contains("同意") || trimmed == "好" || trimmed == "好的" || trimmed == "开启") && !declined
            if (!agreed) {
                return ToolResult(
                    callId = "",
                    content = "用户暂不同意开启「${capabilityLabel(capability)}」权限（选择：$userChoice）。请停止需要该权限的操作并向用户说明。",
                    isError = true,
                )
            }
        }

        val result = host.permissions.request(capability)
        val success = result.status == PermissionStatus.Granted
        val message = if (success) {
            result.message.ifBlank { "已成功开启「${capabilityLabel(capability)}」权限。请继续执行接下来的操作。" }
        } else {
            result.message
        }
        return ToolResult(
            callId = "",
            content = message,
            isError = !success,
        )
    }

    private fun capabilityLabel(capability: PermissionCapability): String = when (capability) {
        PermissionCapability.Microphone -> "麦克风"
        PermissionCapability.Notifications -> "通知"
        PermissionCapability.Accessibility -> "设备操作（无障碍服务）"
        PermissionCapability.ExactAlarm -> "精确闹钟（定时提醒）"
        PermissionCapability.Overlay -> "悬浮窗（显示在其他应用上层）"
        PermissionCapability.BatteryOptimization -> "忽略电池优化（后台运行）"
    }

    private fun promptForCapability(capability: PermissionCapability): String = when (capability) {
        PermissionCapability.Accessibility ->
            "🤖 自动化操作手机需要使用【无障碍服务】权限，方便智能体在屏幕上为你点击和输入。是否现在前往系统设置开启？"
        PermissionCapability.Overlay ->
            "🪟 跨应用操作手机需要【显示在其他应用上层】权限，方便展示操作胶囊与随时暂停。是否现在前往系统设置开启？"
        PermissionCapability.ExactAlarm ->
            "⏰ 托管定时任务需要【精确闹钟】权限，确保到点准时唤醒执行。是否现在前往系统设置开启？"
        PermissionCapability.BatteryOptimization ->
            "🔋 长时间后台任务建议开启【忽略电池优化】，防止应用在后台被系统休眠清理。是否现在开启？"
        PermissionCapability.Notifications ->
            "🔔 接收任务完成和后台警报需要开启【通知】权限。是否现在前往系统设置开启？"
        PermissionCapability.Microphone ->
            "🎙️ 语音对话需要开启【麦克风】权限。是否现在开启？"
    }
}
