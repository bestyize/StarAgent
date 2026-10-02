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
        "为当前明确要执行的功能检查并申请最小 Android 权限。capability 可选 microphone、notifications、accessibility、exact_alarm。运行时权限会显示系统弹窗；accessibility 和 exact_alarm 会打开对应系统设置页，必须由用户亲自确认。只在用户当前确实要使用该功能时调用，不要在后台任务中申请。",
        permissionToolSchema {
            putJsonObject("capability") {
                put("type", "string")
                put("enum", buildJsonArray {
                    add("microphone")
                    add("notifications")
                    add("accessibility")
                    add("exact_alarm")
                })
                put("description", "要申请的 StarAgent 功能权限")
            }
        },
    )

    // 此工具只发起受 Android 管理的权限界面；最终授权始终由用户完成。
    override val timeoutMillis: Long = 5 * 60 * 1000L

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val capability = when (args.string("capability")) {
            "microphone" -> PermissionCapability.Microphone
            "notifications" -> PermissionCapability.Notifications
            "accessibility" -> PermissionCapability.Accessibility
            "exact_alarm" -> PermissionCapability.ExactAlarm
            else -> return ToolResult("", "capability 缺失或不受支持。", true)
        }

        if (host.permissions.status(capability) == PermissionStatus.Granted) {
            return ToolResult("", "所需权限已经开启。")
        }

        val result = host.permissions.request(capability)
        return ToolResult(
            callId = "",
            content = result.message,
            isError = result.status == PermissionStatus.Unsupported,
        )
    }
}
