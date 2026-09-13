package com.starringbox.staragent.core.data

/**
 * 从模型回复里解析「跳过」标记。
 *
 * 群聊是轮流发言：轮到某位成员时，它可以选择这一轮不开口。判定方式与 [TaskBlock] 同一套——
 * 约定一个 fenced block，解析失败一律退化成"正常发言"。绝不能因为模型没按格式输出，
 * 就让它的发言凭空消失。
 */
internal object SkipBlock {

    private val fence = Regex("```skip\\s*```", RegexOption.IGNORE_CASE)

    /** 兜底：模型懒得写围栏时，整条回复恰好只有这些词也算跳过。 */
    private val bareTokens = setOf("跳过", "跳过本轮", "本次跳过", "skip", "[skip]", "（跳过）", "(跳过)")

    data class Parsed(val remainingText: String, val skipped: Boolean)

    fun extract(raw: String): Parsed {
        val match = fence.find(raw)
        val remaining = if (match != null) raw.removeRange(match.range).trim() else raw.trim()
        // 只有"整条回复恰好是一个跳过词"才算数。正文里出现「跳过」不算——
        // 否则一句正常的"我建议跳过这一步"会被误判成不发言。
        val bare = match == null && remaining.lowercase() in bareTokens
        return Parsed(remainingText = remaining, skipped = match != null || bare)
    }

    /**
     * 追加到群聊系统提示里，说明什么时候、以什么格式跳过。
     *
     * 和 [TaskBlock.INSTRUCTION] 一样，关键是划清界限：不列明白，模型要么该跳的时候硬说，
     * 要么为了少说话而对什么问题都跳。
     */
    const val INSTRUCTION: String =
        "【什么时候跳过】如果你对这个问题没有新的补充，或者你的专长与它无关，" +
            "就跳过这次发言——只输出下面这个代码块，块内不要有任何文字：\n" +
            "```skip\n" +
            "```\n" +
            "拿不准时正常发言，不要为了跳过而跳过。"
}

/** 群聊里一条回复的最终去向。 */
sealed interface GroupReplyOutcome {
    data class Speak(val text: String) : GroupReplyOutcome
    data class Skip(val reason: String) : GroupReplyOutcome
}

/**
 * 决定一条群聊回复该怎么落到讨论里。
 *
 * 两件事都做：先判跳过，再抹掉任务块——群聊是纯讨论，永远不该冒出任务草稿卡片。
 *
 * 这里**不用** [resolveReply] 的 `allowTasks = false` 分支是有意的：那条路径在
 * "整条回复只有一个任务块"时会 `ifBlank { raw }` 退回原文，反而把 JSON 露给用户。
 * 所以直接剪，剪完为空就不发言。
 */
internal fun resolveGroupReply(raw: String): GroupReplyOutcome {
    val skip = SkipBlock.extract(raw)
    val taskBlock = TaskBlock.extract(skip.remainingText)
    val cleaned = (taskBlock?.remainingText ?: skip.remainingText).trim()

    return when {
        skip.skipped -> GroupReplyOutcome.Skip(reason = cleaned)
        cleaned.isNotBlank() -> GroupReplyOutcome.Speak(cleaned)
        // 剪完什么都不剩：模型只吐了个任务块。当成这一轮没发言，但绝不回退到原文
        taskBlock != null -> GroupReplyOutcome.Skip(reason = "")
        else -> GroupReplyOutcome.Speak(raw.trim())
    }
}
