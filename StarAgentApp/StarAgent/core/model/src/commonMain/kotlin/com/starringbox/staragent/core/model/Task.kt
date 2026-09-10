package com.starringbox.staragent.core.model

/** 一次性 / 定时 / 周期三种任务形态。 */
enum class Recurrence(val label: String) {
    Once("一次性"),
    Daily("每天"),
    Weekly("每周"),
    Monthly("每月"),
}

enum class TaskStatus {
    Active,
    Paused,
    Done,
}

/**
 * 托管任务。用户交代一次，智能体长期负责。
 * 任务由对话创建，因此始终回指 [agentId]，结果也回流到那条对话。
 */
data class AgentTask(
    val id: String,
    val agentId: String,
    val title: String,
    val recurrence: Recurrence,
    val scheduleLabel: String,
    val nextRunLabel: String,
    val lastRunLabel: String? = null,
    val status: TaskStatus = TaskStatus.Active,
)

/**
 * 任务草稿。用户用自然语言说"每天9点帮我汇总AI新闻"时，
 * 智能体识别出可托管的意图，把它解析成这张已填好参数的卡片，
 * 用户确认后才会真正创建任务。
 */
data class TaskDraft(
    val title: String,
    val recurrence: Recurrence,
    val scheduleLabel: String,
    val channelLabel: String,
    val skillLabels: List<String> = emptyList(),
) {
    fun toTask(id: String, agentId: String, nextRunLabel: String): AgentTask = AgentTask(
        id = id,
        agentId = agentId,
        title = title,
        recurrence = recurrence,
        scheduleLabel = scheduleLabel,
        nextRunLabel = nextRunLabel,
    )
}
