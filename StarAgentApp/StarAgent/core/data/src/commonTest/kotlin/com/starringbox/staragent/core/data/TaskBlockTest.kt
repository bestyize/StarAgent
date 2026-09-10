package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Recurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「对话即任务创建」现在由模型输出任务块来驱动，
 * 解析器是这条链路上最容易出问题、也最该被守住的一环：
 * 模型没按格式输出时，绝不能因此让用户看不到它的正常回答。
 */
class TaskBlockTest {

    @Test
    fun extractsDraftAndStripsTheBlockFromTheVisibleReply() {
        val reply = """
            好的，我给你建一个每天早上的汇总。
            ```task
            {"title":"AI 新闻汇总","recurrence":"daily","schedule":"每天 09:00","channel":"推送到微信","skills":["网页检索"]}
            ```
        """.trimIndent()

        val parsed = TaskBlock.extract(reply)

        assertEquals("AI 新闻汇总", parsed?.draft?.title)
        assertEquals(Recurrence.Daily, parsed?.draft?.recurrence)
        assertEquals("每天 09:00", parsed?.draft?.scheduleLabel)
        assertEquals("推送到微信", parsed?.draft?.channelLabel)
        assertEquals(listOf("网页检索"), parsed?.draft?.skillLabels)
        assertTrue(parsed!!.remainingText.contains("我给你建一个"))
        assertFalse(parsed.remainingText.contains("```"))
    }

    @Test
    fun plainReplyIsLeftUntouched() {
        assertNull(TaskBlock.extract("今天杭州多云，18 到 24 度，出门带件外套就行。"))
    }

    @Test
    fun malformedBlockIsIgnoredInsteadOfThrowing() {
        assertNull(TaskBlock.extract("```task\n这不是 JSON\n```"))
        assertNull(TaskBlock.extract("```task\n{\"schedule\":\"每天 09:00\"}\n```"))
    }

    @Test
    fun recurrenceFallsBackToDailyWhenUnrecognised() {
        val parsed = TaskBlock.extract("记下了。\n```task\n{\"title\":\"喝水提醒\",\"recurrence\":\"every-few-hours\"}\n```")
        assertEquals(Recurrence.Daily, parsed?.draft?.recurrence)
        assertEquals("每天 09:00", parsed?.draft?.scheduleLabel)
    }

    @Test
    fun weeklyRecurrenceIsParsed() {
        val parsed = TaskBlock.extract(
            "好的。\n```task\n{\"title\":\"周报生成\",\"recurrence\":\"weekly\",\"schedule\":\"每周一 09:00\"}\n```"
        )
        assertEquals(Recurrence.Weekly, parsed?.draft?.recurrence)
    }

    // ── 任务能力开关 ───────────────────────────────
    // 陪聊、答疑类智能体不该动不动就弹任务卡片，所以这个开关是硬的：
    // 关掉时提示里不会出现任务指令，而且即便模型多吐了块，也不会变成卡片。

    @Test
    fun plainChatStaysPlainEvenWhenTaskAbilityIsOn() {
        val outcome = resolveReply("今天杭州多云，18 到 24 度。", allowTasks = true)

        assertTrue(outcome is ReplyOutcome.Plain)
        assertEquals("今天杭州多云，18 到 24 度。", (outcome as ReplyOutcome.Plain).text)
    }

    @Test
    fun agentWithTaskAbilityGetsADraftCard() {
        val reply = "好的，我每天十点提醒你。\n```task\n" +
            "{\"title\":\"喝水提醒\",\"recurrence\":\"daily\",\"schedule\":\"每天 10:00\"}\n```"

        val outcome = resolveReply(reply, allowTasks = true)

        assertTrue(outcome is ReplyOutcome.AsTask)
        outcome as ReplyOutcome.AsTask
        assertEquals("喝水提醒", outcome.draft.title)
        assertEquals("每天 10:00", outcome.draft.scheduleLabel)
        assertFalse(outcome.text.contains("```"))
    }

    @Test
    fun agentWithoutTaskAbilityNeverGetsADraftCard() {
        val reply = "好的，记下了。\n```task\n{\"title\":\"喝水提醒\",\"recurrence\":\"daily\"}\n```"

        val outcome = resolveReply(reply, allowTasks = false)

        assertTrue(outcome is ReplyOutcome.Plain)
        outcome as ReplyOutcome.Plain
        assertTrue(outcome.text.contains("记下了"))
        // 块被抹掉，用户不会看到原始 JSON
        assertFalse(outcome.text.contains("```"))
        assertFalse(outcome.text.contains("task"))
    }
}
