package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.llm.LlmRole
import com.starringbox.staragent.core.llm.LlmTurn
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.Author
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.model.TalkPlan

/**
 * 群聊的纯逻辑：怎么把讨论记录拼成模型输入、给每个成员什么提示、这一场要调用几次。
 *
 * 单独抽出来是为了能单测——AppState 里那部分只管编排与状态写入。
 */
internal object GroupTalk {

    /** 发给模型的上下文上限，与单聊一致。 */
    private const val MAX_TURNS = 24

    /**
     * 群会话在 `conversations` 里的 key。
     *
     * 复用同一个 map 而不是另开一份：`messagesOf` / `lastMessage` / `busy` 本来就是按
     * 任意字符串 key 泛化的，加前缀就能永不与 agent id（`a-xing`、`agent-N`）相撞。
     */
    fun key(groupId: String): String = "group:$groupId"

    fun plan(group: AgentGroup): TalkPlan =
        TalkPlan(memberCount = group.memberIds.size, rounds = group.rounds)

    /**
     * 从用户这条消息里找出被 @ 到的成员，按群里的顺序返回。
     *
     * 按名字长度从长到短匹配：不然「小星」会把「小星星」吃掉一半。
     * 一个都没匹配上就返回空——那就是普通消息，照常轮流。
     */
    fun mentionedIds(text: String, roster: List<Agent>): List<String> {
        if (!text.contains('@')) return emptyList()

        val byLength = roster.sortedByDescending { it.name.length }
        val hit = mutableSetOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '@') {
                index++
                continue
            }
            val member = byLength.firstOrNull { text.startsWith(it.name, index + 1) }
            if (member == null) {
                // 认不出来的 @（比如 @所有人、@一个不存在的名字）当普通文本
                index++
                continue
            }
            hit += member.id
            index += 1 + member.name.length
        }
        // 按群里的顺序返回，发言顺序才是稳定的
        return roster.filter { it.id in hit }.map { it.id }
    }

    /**
     * 群聊历史。
     *
     * 关键约定：**自己说的当 Assistant，别人说的当 User 并带上「【名字】」前缀**。
     * 若把所有人都一律当 Assistant，模型会以为自己说过他们的话，几个人的人设会互相污染。
     *
     * [LlmTurn] 没有 name 字段（OpenAI 各家兼容端点对 `name` 的支持不一，Anthropic 原生协议
     * 根本没有），所以发言人只能靠文本前缀表达——纯文本，跨协议都可靠。
     */
    fun historyFor(
        messages: List<ChatMessage>,
        self: Agent,
        roster: List<Agent>,
    ): List<LlmTurn> {
        val turns = messages.mapNotNull { message ->
            when (val body = message.body) {
                is MessageBody.Text -> {
                    val text = body.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (message.author == Author.User) {
                        LlmTurn(LlmRole.User, text)
                    } else {
                        val name = roster.firstOrNull { it.id == message.speakerAgentId }?.name ?: "成员"
                        val role = if (message.speakerAgentId == self.id) LlmRole.Assistant else LlmRole.User
                        LlmTurn(role, "【$name】$text")
                    }
                }

                // 图片只有用户会发。别的成员不会产图，也不需要把图带进讨论上下文。
                is MessageBody.Image ->
                    if (message.author == Author.User) LlmTurn(LlmRole.User, body.caption, body.assets) else null

                // Notice / Skipped / 各类卡片都不进上下文：跳过就是"没说"，卡片不是发言
                else -> null
            }
        }
        return mergeSameRole(turns).takeLast(MAX_TURNS)
    }

    /**
     * 合并连续同角色的轮次。
     *
     * **这是硬需求**：用户提问后接着 A、B 依次发言，会产生连续多条 User，
     * 而 Anthropic 原生协议要求 user / assistant 严格交替，否则直接 400。
     */
    fun mergeSameRole(turns: List<LlmTurn>): List<LlmTurn> = buildList {
        turns.forEach { turn ->
            val last = lastOrNull()
            val canMerge = last != null && last.role == turn.role &&
                last.images.isEmpty() && turn.images.isEmpty()
            if (canMerge) {
                this[lastIndex] = last.copy(text = last.text + "\n" + turn.text)
            } else {
                add(turn)
            }
        }
    }

    /**
     * 某个成员在群里收到的系统提示。
     *
     * 与单聊相比：保留"我是谁 + 响应模式"，**减去任务指令**（群聊是纯讨论），
     * **加上群上下文**——有谁、现在第几轮、怎么发言、什么时候跳过。
     *
     * "刚才谁说了什么"不写在这里，由 [historyFor] 承载。否则每条消息都要把整段讨论重发一遍。
     */
    fun promptFor(
        group: AgentGroup,
        self: Agent,
        round: Int,
        roster: List<Agent>,
        mode: ResponseMode,
        addressed: Boolean = false,
    ): String = buildString {
        append("你是「").append(self.name).append("」。")
        if (self.persona.isNotBlank()) {
            append(self.persona.trim().trimEnd('。', '！', '!', '.')).append("。")
        }

        append("\n\n【群聊】你正在群「").append(group.name).append("」里，和 ")
            .append((roster.size - 1).coerceAtLeast(0))
            .append(" 位智能体一起讨论用户提出的问题。")
        append("\n群成员：")
        append(roster.joinToString("、") { member ->
            if (member.id == self.id) "「${member.name}」(你)" else "「${member.name}」"
        })
        append("。发言按顺序进行，一次只有一个人说话。")
        append("\n现在是第 ").append(round).append(" / ").append(group.rounds).append(" 轮，轮到你发言。")

        append("\n\n【怎么发言】")
        if (addressed) {
            append("\n- 用户这条消息点名找的就是你，直接回答他的问题，不要只说「我补充一点」。")
        }
        append("\n- 只代表你自己。不要替其他成员说话，也不要复述或总结他们刚说过的话。")
        append("\n- 别人已经讲过的点不要重复；有分歧就直接指出来，有补充就讲你这个角度看到的东西。")
        append("\n- 最多 3 句，说完就停，把话头留给下一位。")

        append("\n\n").append(SkipBlock.INSTRUCTION)

        append("\n\n").append(
            when (mode) {
                ResponseMode.Fast -> "发言尽量简短直接，先给结论，不要客套。"
                ResponseMode.Standard -> "先给结论，再给必要的依据。"
                ResponseMode.Deep -> "发言前充分思考，给出推理过程与依据，必要时分点展开。"
            }
        )
    }
}
