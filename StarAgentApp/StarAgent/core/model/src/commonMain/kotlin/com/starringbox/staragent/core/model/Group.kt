package com.starringbox.staragent.core.model

import kotlinx.serialization.Serializable

/**
 * 智能体群组：几位智能体在一个群里围绕用户的问题互相讨论。
 *
 * 成员只记 id，不内嵌 [Agent]——智能体改了人设或换了模型，群成员自然跟随，
 * 这与 [MemoryItem.agentId]、[AgentTask.agentId] 的引用方式一致。
 */
@Serializable
data class AgentGroup(
    val id: String,
    val name: String,
    val memberIds: List<String> = emptyList(),
    /**
     * 讨论轮数：每人发言一次算一轮。
     * 一次提问触发的模型调用次数 = [memberIds].size × rounds，界面上要提前告诉用户。
     */
    val rounds: Int = 1,
    /** 群头像的字形，交给 AgentAvatar 渲染，与 Agent.glyph 同一套契约。 */
    val glyph: String = "群",
    val accentIndex: Int = 5,
    val pinned: Boolean = false,
) {
    /** 一次提问要花掉多少次模型调用。 */
    val callsPerMessage: Int get() = memberIds.size * rounds
}

/** 讨论计划：把"要调用几次"在开跑之前就算清楚，好让界面提前告知用户成本。 */
data class TalkPlan(val memberCount: Int, val rounds: Int) {
    val calls: Int get() = memberCount * rounds
}
