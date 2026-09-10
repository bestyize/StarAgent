package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.TaskDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 从模型回复里解析出「任务块」。
 *
 * 系统提示要求模型在判断出"这件事可以托管"时，在回复末尾附一个 ```task 代码块。
 * 这样既保留了真实的模型对话，又让"对话即任务创建"这个招牌交互继续成立。
 *
 * 解析失败就当成普通回复——绝不能因为模型没按格式输出，就让用户看不到它的回答。
 */
internal object TaskBlock {

    private val blockRegex = Regex("```task\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    data class Parsed(val remainingText: String, val draft: TaskDraft)

    fun extract(raw: String): Parsed? {
        val match = blockRegex.find(raw) ?: return null
        val json = runCatching {
            Json.parseToJsonElement(match.groupValues[1].trim()).jsonObject
        }.getOrNull() ?: return null

        val title = json["title"].asText()?.takeIf { it.isNotBlank() } ?: return null
        val schedule = json["schedule"].asText()?.takeIf { it.isNotBlank() } ?: "每天 09:00"
        val channel = json["channel"].asText()?.takeIf { it.isNotBlank() } ?: "在 App 内提醒"
        val skills = json["skills"]
            ?.let { element -> runCatching { element.jsonArray.mapNotNull { it.asText() } }.getOrNull() }
            .orEmpty()

        val recurrence = when (json["recurrence"].asText()?.lowercase()) {
            "once" -> Recurrence.Once
            "weekly" -> Recurrence.Weekly
            "monthly" -> Recurrence.Monthly
            else -> Recurrence.Daily
        }

        return Parsed(
            remainingText = raw.removeRange(match.range).trim(),
            draft = TaskDraft(
                title = title,
                recurrence = recurrence,
                scheduleLabel = schedule,
                channelLabel = channel,
                skillLabels = skills,
            ),
        )
    }

    /**
     * 追加到系统提示末尾，告诉模型什么时候、以什么格式输出任务块。
     *
     * 关键是划清"把事情交出去"和"现在就能回答"的界限——
     * 早先的版本只列了「每天」「每周」这类关键词，结果模型很容易在普通对话里也弹出任务卡片。
     */
    const val INSTRUCTION: String =
        "【任务识别】当且仅当用户在**把一件事交给你去做**时，才在回复末尾另起一行输出下面这个代码块，" +
            "块内只放 JSON，不要加多余文字：\n" +
            "```task\n" +
            "{\"title\":\"任务名\",\"recurrence\":\"daily|weekly|monthly|once\"," +
            "\"schedule\":\"每天 09:00\",\"channel\":\"推送到微信\",\"skills\":[\"网页检索\"]}\n" +
            "```\n" +
            "该输出的两种情形：\n" +
            "1. 用户要你今后反复做某件事（「每天…」「每周…」）；\n" +
            "2. 用户要你在将来某个时间点主动触发（「明天下午三点提醒我…」）。\n" +
            "**不该输出**的两种情形：\n" +
            "1. 用户在提问或闲聊（「这个怎么用」「帮我解释一下」）；\n" +
            "2. 用户要的东西你现在就能直接给出来（「把这段话翻译一下」「把这周的进展整理成周报」）——" +
            "直接回答即可，不要建任务。\n" +
            "拿不准时不要输出任务块。"
}

/**
 * 模型回复的最终去向：要么是普通回答，要么额外长出一张任务草稿卡片。
 */
sealed interface ReplyOutcome {
    data class Plain(val text: String) : ReplyOutcome
    data class AsTask(val text: String, val draft: TaskDraft) : ReplyOutcome
}

/**
 * 决定一段模型回复该怎么落到对话里。
 *
 * [allowTasks] 是这个智能体的任务能力开关——
 * 关掉时即便模型多吐了任务块，也只把块抹掉，绝不让用户看到原始 JSON。
 */
internal fun resolveReply(raw: String, allowTasks: Boolean): ReplyOutcome {
    val parsed = TaskBlock.extract(raw) ?: return ReplyOutcome.Plain(raw)

    if (!allowTasks) return ReplyOutcome.Plain(parsed.remainingText.ifBlank { raw })

    return ReplyOutcome.AsTask(
        text = parsed.remainingText.ifBlank { "好的，我把它做成一个任务：" },
        draft = parsed.draft,
    )
}

private fun JsonElement?.asText(): String? {
    if (this == null || this is JsonNull) return null
    return (this as? JsonPrimitive)?.content
}
