package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.ToolDefinition
import com.starringbox.staragent.core.model.ToolResult
import com.starringbox.staragent.core.tools.Tool
import com.starringbox.staragent.core.tools.ToolHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 建一条托管任务。替代原来靠 ` ```task ` 文本块的暗号机制。
 *
 * 好处不只是"更规范"：不用再靠正则、不用再担心模型格式跑偏，
 * 而且模型能改能查能删（原来只能建），建完还能拿到 id 接着引用。
 */
object CreateTaskTool : Tool {

    override val definition = ToolDefinition(
        name = "create_task",
        description = "把一件事托管下来，今后按时替用户做。" +
            "**只在两件事上调用**：用户要你今后反复做某件事（「每天…」「每周…」），" +
            "或者要你在将来某个时间点主动触发（「明天下午三点提醒我…」）。" +
            "**不要**给「现在就能回答」的请求建任务（提问、翻译、整理一段已有内容）——那些直接回答即可。" +
            "用户没把话说完、你拿不准时间或频率时，**先问清楚再建**，不要猜。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "任务名，一句话说清要做什么")
                }
                putJsonObject("recurrence") {
                    put("type", "string")
                    put("description", "重复方式")
                    put("enum", buildJsonArray {
                        Recurrence.entries.forEach { add(it.name) }
                    })
                }
                putJsonObject("schedule") {
                    put("type", "string")
                    put("description", "什么时候做，写成给人看的话，如「每天 09:00」「每周一 09:00」")
                }
                putJsonObject("note") {
                    put("type", "string")
                    put("description", "补充说明，可选")
                }
            }
            putJsonArray("required") {
                add("title")
                add("recurrence")
                add("schedule")
            }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val title = args.stringOrNull("title")
        if (title.isNullOrBlank()) {
            return ToolResult(callId = "", content = "title 不能为空", isError = true)
        }

        val rawRecurrence = args.stringOrNull("recurrence")
            ?: return ToolResult(callId = "", content = "缺参数 recurrence", isError = true)

        val recurrence = Recurrence.entries.firstOrNull { it.name == rawRecurrence }
            ?: return ToolResult(
                callId = "",
                content = "recurrence 只能是这些之一：${Recurrence.entries.joinToString("、") { it.name }}",
                isError = true,
            )

        val schedule = args.stringOrNull("schedule")
        if (schedule.isNullOrBlank()) {
            return ToolResult(callId = "", content = "schedule 不能为空", isError = true)
        }

        val id = host.tasks.create(
            agentId = host.agentId,
            title = title.trim(),
            recurrence = recurrence,
            scheduleLabel = schedule.trim(),
            note = args.stringOrNull("note")?.takeIf { it.isNotBlank() }?.trim(),
        )

        // 把 id 还给模型，它后面可能要引用这个任务
        return ToolResult(callId = "", content = "已创建任务「${title.trim()}」，id 是 $id。")
    }
}
