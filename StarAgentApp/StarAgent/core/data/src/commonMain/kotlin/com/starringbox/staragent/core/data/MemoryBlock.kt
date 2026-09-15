package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MemoryCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 从模型回复里解析出「记忆块」。
 *
 * 和 [TaskBlock] 同一套路：系统提示要求模型在判断出"这句值得长期记住"时，
 * 在回复末尾附一个 ```memory 代码块。**没有这条路，智能体就只能嘴上说"记住了"**——
 * 记忆页里什么都不会有。这正是用户当面抓到的问题：功能看着有，底下没人写。
 *
 * 解析失败就当成普通回复：绝不能因为模型没写对格式，就让它的话变成一串 JSON。
 */
internal object MemoryBlock {

    private val blockRegex = Regex("```memory\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /** 一条待写入的记忆。 */
    data class Item(val text: String, val category: MemoryCategory)

    /** [remainingText] 是剥掉所有记忆块之后剩下的正文。 */
    data class Parsed(val remainingText: String, val items: List<Item>)

    /**
     * 一条都没解析出来时返回 null，正文保持原样。
     *
     * 这个取舍是有意的：宁可让用户看到模型写坏的那一段，也不要假装记下了——
     * "嘴上说记住了、实际没写"正是这次要修的 bug，不能换个地方再犯一遍。
     */
    fun extract(raw: String): Parsed? {
        val matches = blockRegex.findAll(raw).toList()
        if (matches.isEmpty()) return null

        val items = matches.mapNotNull { match ->
            val json = runCatching {
                Json.parseToJsonElement(match.groupValues[1].trim()).jsonObject
            }.getOrNull() ?: return@mapNotNull null

            val text = json["text"].asText()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Item(text, categoryOf(json["category"].asText()))
        }
        if (items.isEmpty()) return null

        return Parsed(remainingText = strip(raw, matches.map { it.range }), items = items)
    }

    /** 按区间把块从正文里挖掉。逆序处理，免得前面的删除把后面的下标带偏。 */
    private fun strip(raw: String, ranges: List<IntRange>): String = buildString {
        var cursor = 0
        ranges.sortedBy { it.first }.forEach { range ->
            append(raw, cursor, range.first)
            cursor = range.last + 1
        }
        append(raw, cursor, raw.length)
    }.trim()

    private fun categoryOf(raw: String?): MemoryCategory = when (raw?.lowercase()) {
        "person" -> MemoryCategory.Person
        "habit" -> MemoryCategory.Habit
        "experience" -> MemoryCategory.Experience
        "decision" -> MemoryCategory.Decision
        // 认不出来就归到最常见、也最不容易让人意外的一类
        else -> MemoryCategory.Preference
    }

    /**
     * 追加到系统提示末尾，告诉模型什么时候、以什么格式输出记忆块。
     *
     * 关键是划清"值得长期记住"和"只是这一句里提到"的界限——
     * 不写清楚的话，模型会把"我今晚加班"这种明天就不成立的事也记下来，
     * 记忆页很快就会被一次性信息淹掉。
     */
    const val INSTRUCTION: String =
        "【记忆】当用户主动让你记住**关于他自己的长期信息**时，在回复末尾另起一行输出下面这个代码块，" +
            "块内只放 JSON，不要加多余文字：\n" +
            "```memory\n" +
            "{\"text\":\"一句话陈述\",\"category\":\"preference|person|habit|experience|decision\"}\n" +
            "```\n" +
            "该输出的五类：\n" +
            "1. preference——偏好（「我喜欢…」「我习惯用…」）；\n" +
            "2. person——重要的人（「我同事小林…」「我女儿…」）；\n" +
            "3. habit——生活习惯（「我一般早上七点起」）；\n" +
            "4. experience——经历（「我去年在成都待过半年」）；\n" +
            "5. decision——他自己做过的决定（「我决定以后都用…」）。\n" +
            "**不该输出**的情形：\n" +
            "1. 一次性的、过了就不成立的信息（「我今晚加班」「帮我把这段翻译一下」）；\n" +
            "2. 用户在提问或闲聊；\n" +
            "3. 用户明确说了别记。\n" +
            "text 写成一句完整的第三人称陈述，不要照抄原话" +
            "（写「用户 1995 年生」而不是「我 95 年的」）；" +
            "一次有多件独立的事就输出多个块。\n" +
            "拿不准时不要输出记忆块。"
}
