package com.starringbox.staragent.core.tools.builtin

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskStatus
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

/**
 * 列出当前智能体的托管任务。
 *
 * **必须把 id 带上**——模型要靠它接着 update 或 cancel。
 * 不带 id 的话模型只能说"我看到有个看新闻的任务"，改不动。
 */
object ListTasksTool : Tool {

    override val definition = ToolDefinition(
        name = "list_tasks",
        description = "看看这位用户现在托管了哪些任务。" +
            "用户问起「我有哪些任务」「那个任务还在吗」，或者你要改/取消某个任务但不确定它的 id 时，用这个。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val tasks = host.tasks.list(host.agentId)
        if (tasks.isEmpty()) {
            // 说成一句话。返回空串的话模型会以为工具坏了
            return ToolResult(callId = "", content = "现在没有托管中的任务。")
        }

        val text = tasks.joinToString("\n") { task ->
            "- ${task.title}（id: ${task.id}）｜${task.recurrence.label}｜" +
                "下次：${task.nextRunLabel}${if (task.status == TaskStatus.Paused) "｜已暂停" else ""}"
        }
        return ToolResult(callId = "", content = "共 ${tasks.size} 个任务：\n$text")
    }
}

/**
 * 改一个托管任务。只改传进来的字段，没传的保持原样。
 *
 * 只给 title 和 schedule 两个可改项：频率改了要重排期、笔记改了意义不大，
 * 而这两样是用户最常说的（"改成晚上十点""改叫别的名字"）。
 */
object UpdateTaskTool : Tool {

    override val definition = ToolDefinition(
        name = "update_task",
        description = "改一个已经托管的任务的名称或执行时间。" +
            "改之前先用 list_tasks 拿到它的 id。用户说「改成…」「不要每天了，改成每周」时用这个。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "任务的 id，从 list_tasks 拿")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "新名称，不改就不传")
                }
                putJsonObject("schedule") {
                    put("type", "string")
                    put("description", "新的执行时间，写成给人看的话，不改就不传")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.stringOrNull("id")
            ?: return ToolResult(callId = "", content = "缺参数 id，先用 list_tasks 查一下", isError = true)

        val title = args.stringOrNull("title")?.takeIf { it.isNotBlank() }
        val schedule = args.stringOrNull("schedule")?.takeIf { it.isNotBlank() }

        if (title == null && schedule == null) {
            return ToolResult(
                callId = "",
                content = "没说改什么。请传 title 或 schedule 中的一个。",
                isError = true,
            )
        }

        val ok = host.tasks.update(host.agentId, id, title, schedule)
        return if (ok) {
            ToolResult(callId = "", content = "已改好任务 $id。")
        } else {
            ToolResult(
                callId = "",
                content = "没找到 id 是「$id」的任务，先用 list_tasks 看一眼。",
                isError = true,
            )
        }
    }
}

/**
 * 取消一个托管任务。
 *
 * 实现是**置为 Done 而不是删掉**——删了用户就再也看不到它出过什么，
 * 任务详情页的执行历史也就断了。
 */
object CancelTaskTool : Tool {

    override val definition = ToolDefinition(
        name = "cancel_task",
        description = "取消一个托管任务，之后不再执行。" +
            "用户说「不用了」「取消掉」「停掉那个」时用。id 从 list_tasks 拿。",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "任务的 id，从 list_tasks 拿")
                }
            }
            putJsonArray("required") { add("id") }
        },
    )

    override suspend fun execute(args: JsonObject, host: ToolHost): ToolResult {
        val id = args.stringOrNull("id")
            ?: return ToolResult(callId = "", content = "缺参数 id，先用 list_tasks 查一下", isError = true)

        val ok = host.tasks.cancel(host.agentId, id)
        return if (ok) {
            ToolResult(callId = "", content = "已取消任务 $id。")
        } else {
            ToolResult(
                callId = "",
                content = "没找到 id 是「$id」的任务，先用 list_tasks 看一眼。",
                isError = true,
            )
        }
    }
}
