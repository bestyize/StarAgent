package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ResponseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 群聊的纯逻辑：拼历史、写提示、算成本。
 *
 * 这里最要命的一条是**角色必须交替**——不回合并连续同角色，Anthropic 原生协议会直接 400。
 */
class GroupTalkTest {

    private val xing = Agent(
        id = "a-xing", name = "小星", glyph = "星",
        persona = "陪你聊天的私人助手", modelId = "deepseek:0",
    )
    private val work = Agent(
        id = "a-work", name = "工作助手", glyph = "工",
        persona = "帮你打理工作上的事", modelId = "deepseek:0",
    )
    private val roster = listOf(xing, work)

    private val group = AgentGroup(id = "g-1", name = "三人行", memberIds = listOf("a-xing", "a-work"), rounds = 2)

    private fun message(
        id: String,
        author: Author,
        body: MessageBody,
        speaker: String? = null,
        round: Int? = null,
    ) = ChatMessage(id = id, author = author, body = body, timeLabel = "刚刚", speakerAgentId = speaker, roundIndex = round)

    @Test
    fun planCountsMembersTimesRounds() {
        val plan = GroupTalk.plan(group)
        assertEquals(2, plan.memberCount)
        assertEquals(2, plan.rounds)
        assertEquals(4, plan.calls)
    }

    @Test
    fun ownSpeechIsAssistantAndOthersArePrefixedUsers() {
        val messages = listOf(
            message("m1", Author.User, MessageBody.Text("该不该做真机操作？")),
            message("m2", Author.Agent, MessageBody.Text("我觉得二期再做好。"), speaker = "a-xing"),
            message("m3", Author.Agent, MessageBody.Text("同意，一期先打磨对话。"), speaker = "a-work"),
        )

        val turns = GroupTalk.historyFor(messages, self = work, roster = roster)

        // 用户提问 + 小星的发言合并成一条 User（连着两条 User 会被 Anthropic 拒），
        // 工作助手自己的发言是 Assistant
        assertEquals(2, turns.size)
        assertEquals(LlmRole.User, turns[0].role)
        assertTrue(turns[0].text.contains("该不该做真机操作"))
        assertTrue(turns[0].text.contains("【小星】我觉得二期再做好。"))

        assertEquals(LlmRole.Assistant, turns[1].role)
        assertEquals("【工作助手】同意，一期先打磨对话。", turns[1].text)
    }

    @Test
    fun rolesNeverRepeatConsecutively() {
        // 用户 + 两位成员各说一句，全是 User 侧，必须被压成一条
        val messages = listOf(
            message("m1", Author.User, MessageBody.Text("问题")),
            message("m2", Author.Agent, MessageBody.Text("A 说"), speaker = "a-xing"),
            message("m3", Author.Agent, MessageBody.Text("B 说"), speaker = "a-work"),
            message("m4", Author.Agent, MessageBody.Text("A 又说"), speaker = "a-xing"),
        )

        val turns = GroupTalk.historyFor(messages, self = work, roster = roster)

        turns.zipWithNext { a, b ->
            assertTrue(a.role != b.role, "出现了连续同角色，Anthropic 会 400：${a.role} -> ${b.role}")
        }
    }

    @Test
    fun skippedAndNoticeMessagesStayOutOfHistory() {
        val messages = listOf(
            message("m1", Author.User, MessageBody.Text("问题")),
            message("m2", Author.Agent, MessageBody.Skipped(), speaker = "a-xing"),
            message("m3", Author.Agent, MessageBody.Notice("模型调用失败", isError = true), speaker = "a-work"),
            message("m4", Author.Agent, MessageBody.Text("我的看法是……"), speaker = "a-work"),
        )

        val turns = GroupTalk.historyFor(messages, self = work, roster = roster)

        assertTrue(turns.none { it.text.contains("跳过了") })
        assertTrue(turns.none { it.text.contains("模型调用失败") })
        assertTrue(turns.any { it.text.contains("我的看法是") })
    }

    @Test
    fun emptyPlaceholderTextDoesNotEnterHistory() {
        // 流式回复刚建出来时是空字符串，不该进上下文
        val messages = listOf(
            message("m1", Author.User, MessageBody.Text("问题")),
            message("m2", Author.Agent, MessageBody.Text(""), speaker = "a-xing", round = 1),
        )

        val turns = GroupTalk.historyFor(messages, self = work, roster = roster)
        assertEquals(1, turns.size)
        assertEquals(LlmRole.User, turns[0].role)
    }

    @Test
    fun promptNamesTheRosterAndTheRound() {
        val prompt = GroupTalk.promptFor(group, self = work, round = 2, roster = roster, mode = ResponseMode.Standard)

        assertTrue(prompt.contains("工作助手"))
        assertTrue(prompt.contains("小星"))
        assertTrue(prompt.contains("(你)"), "要标出哪个是自己")
        assertTrue(prompt.contains("第 2 / 2 轮"))
        assertTrue(prompt.contains("不要替其他成员说话"))
    }

    @Test
    fun groupPromptNeverCarriesTheTaskInstruction() {
        // 群聊是纯讨论。任务指令一旦混进提示，成员就会开始弹草稿卡片。
        val prompt = GroupTalk.promptFor(group, self = work, round = 1, roster = roster, mode = ResponseMode.Standard)

        assertFalse(prompt.contains("任务识别"), "群聊提示里不该出现任务指令")
        assertFalse(prompt.contains("```task"), "群聊提示里不该出现任务块模板")
        assertTrue(prompt.contains("```skip"), "但必须带上跳过模板")
    }

    @Test
    fun historyIsTrimmedToTheRecentWindow() {
        val many = buildList {
            add(message("u", Author.User, MessageBody.Text("问题")))
            repeat(40) { index ->
                add(message("a$index", Author.Agent, MessageBody.Text("第 $index 句"), speaker = "a-xing"))
            }
        }

        val turns = GroupTalk.historyFor(many, self = work, roster = roster)
        assertTrue(turns.size <= 24, "上下文条数要有上限，实际 ${turns.size}")
    }

    // ── @ 某位成员 ────────────────────────────────

    @Test
    fun mentionFindsTheNamedMember() {
        assertEquals(listOf("a-work"), GroupTalk.mentionedIds("@工作助手 你怎么看", roster))
    }

    @Test
    fun plainMessageMentionsNobody() {
        assertTrue(GroupTalk.mentionedIds("你们俩怎么看这件事", roster).isEmpty())
    }

    @Test
    fun mentionsComeBackInGroupOrderNotTextOrder() {
        // 文本里先提了工作助手，但群里的顺序是小星在前——发言顺序要按群里的来
        val ids = GroupTalk.mentionedIds("@工作助手 和 @小星 都说说", roster)
        assertEquals(listOf("a-xing", "a-work"), ids)
    }

    @Test
    fun longerNameWinsSoShorterOneDoesNotEatIt() {
        val short = Agent(id = "a-x", name = "小星", glyph = "星", persona = "", modelId = "deepseek:0")
        val long = Agent(id = "a-xx", name = "小星星", glyph = "星", persona = "", modelId = "deepseek:0")

        val ids = GroupTalk.mentionedIds("@小星星 你来", listOf(short, long))

        assertEquals(listOf("a-xx"), ids, "@「小星星」不该被认成 @「小星」")
    }

    @Test
    fun mentioningSomeoneOutsideTheGroupIsJustText() {
        assertTrue(GroupTalk.mentionedIds("@所有人 来看", roster).isEmpty())
    }

    @Test
    fun addressedPromptTellsTheMemberToAnswerDirectly() {
        val plain = GroupTalk.promptFor(group, work, 1, roster, ResponseMode.Standard)
        val called = GroupTalk.promptFor(group, work, 1, roster, ResponseMode.Standard, addressed = true)

        assertFalse(plain.contains("点名找的就是你"))
        assertTrue(called.contains("点名找的就是你"))
    }
}
