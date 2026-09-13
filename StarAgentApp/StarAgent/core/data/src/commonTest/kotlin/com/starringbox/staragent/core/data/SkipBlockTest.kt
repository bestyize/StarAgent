package com.starringbox.staragent.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「跳过」是群聊里最容易出问题的一环：判定太松会把正常发言吞掉，太紧又永远不生效。
 *
 * 守住的底线与 TaskBlock 一致——**解析不出来一律当成正常发言**，
 * 绝不能因为模型没按格式输出，就让它的回答消失。
 */
class SkipBlockTest {

    @Test
    fun bareFenceMeansSkipped() {
        val parsed = SkipBlock.extract("```skip\n```")
        assertTrue(parsed.skipped)
        assertEquals("", parsed.remainingText)
    }

    @Test
    fun halfSentenceBeforeTheFenceBecomesTheReason() {
        val parsed = SkipBlock.extract("这题我不太擅长，先不说了。\n```skip\n```")
        assertTrue(parsed.skipped)
        assertEquals("这题我不太擅长，先不说了。", parsed.remainingText)
    }

    @Test
    fun wholeReplyBeingJustTheWordCountsAsSkip() {
        assertTrue(SkipBlock.extract("跳过").skipped)
        assertTrue(SkipBlock.extract("  跳过本轮 ").skipped)
        assertTrue(SkipBlock.extract("SKIP").skipped)
    }

    @Test
    fun mentioningSkipInsideARealAnswerIsNotASkip() {
        // 这句里出现了「跳过」，但它显然是在正常发言——不能因为关键词就把话吞了
        val parsed = SkipBlock.extract("我建议跳过这一步，先把数据补齐再往下做。")
        assertFalse(parsed.skipped)
        assertEquals("我建议跳过这一步，先把数据补齐再往下做。", parsed.remainingText)
    }

    @Test
    fun plainAnswerIsLeftUntouched() {
        val parsed = SkipBlock.extract("我同意小星的看法，再补一点：成本也要算进去。")
        assertFalse(parsed.skipped)
        assertEquals("我同意小星的看法，再补一点：成本也要算进去。", parsed.remainingText)
    }

    @Test
    fun malformedFenceDoesNotThrow() {
        // 没闭合的围栏：匹配不上，于是退化成一整段普通发言
        val parsed = SkipBlock.extract("```skip 后面忘了闭合")
        assertFalse(parsed.skipped)
        assertEquals("```skip 后面忘了闭合", parsed.remainingText)
    }

    @Test
    fun spokenReplyComesBackAsSpeak() {
        val outcome = resolveGroupReply("我觉得应该先做一期，二期再上真机操作。")
        assertTrue(outcome is GroupReplyOutcome.Speak)
        assertEquals("我觉得应该先做一期，二期再上真机操作。", (outcome as GroupReplyOutcome.Speak).text)
    }

    @Test
    fun skipOutcomeCarriesTheReason() {
        val outcome = resolveGroupReply("这是工程问题，不是我的领域。\n```skip\n```")
        assertTrue(outcome is GroupReplyOutcome.Skip)
        assertEquals("这是工程问题，不是我的领域。", (outcome as GroupReplyOutcome.Skip).reason)
    }

    @Test
    fun taskBlockIsStrippedFromGroupReplies() {
        // 群聊是纯讨论。模型就算吐了任务块，也绝不能变成草稿卡片，原始 JSON 更不能露给用户。
        val reply = """
            我建议把节奏放慢一点。
            ```task
            {"title":"慢下来","recurrence":"daily","schedule":"每天 09:00"}
            ```
        """.trimIndent()

        val outcome = resolveGroupReply(reply)
        assertTrue(outcome is GroupReplyOutcome.Speak)
        val text = (outcome as GroupReplyOutcome.Speak).text
        assertTrue(text.contains("把节奏放慢"))
        assertFalse(text.contains("```"))
        assertFalse(text.contains("\"title\""))
    }

    @Test
    fun taskBlockIsNotShownAsASkipReason() {
        // 同时吐了任务块和跳过标记时，理由不能是一段 JSON
        val reply = """
            ```task
            {"title":"慢下来","recurrence":"daily"}
            ```
            ```skip
            ```
        """.trimIndent()

        val outcome = resolveGroupReply(reply)
        assertTrue(outcome is GroupReplyOutcome.Skip)
        assertFalse((outcome as GroupReplyOutcome.Skip).reason.contains("title"))
    }
}
