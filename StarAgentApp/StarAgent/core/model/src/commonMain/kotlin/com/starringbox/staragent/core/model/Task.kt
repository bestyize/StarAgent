package com.starringbox.staragent.core.model

import kotlinx.serialization.Serializable

/**
 * 任务的重复方式，对应设计稿里的任务类型切换。
 *
 * [Conditional] 不是按时间跑，而是满足某个条件才触发（例如"当连接 Wi-Fi 时"），
 * 条件本身写在任务的 [AgentTask.scheduleLabel] 里。
 */
@Serializable
enum class Recurrence(val label: String) {
    Once("一次性"),
    Daily("每天"),
    Weekly("每周"),
    Monthly("每月"),
    Conditional("条件触发"),
}

@Serializable
enum class TaskStatus {
    Active,
    Paused,
    Done,
}

/** 任务优先级。设计稿把它作为卡片上的一个标签（"高优先级"）。 */
@Serializable
enum class TaskPriority(val label: String) {
    High("高优先级"),
    Medium("中优先级"),
    Low("低优先级"),
}

/**
 * 托管任务。用户交代一次，智能体长期负责。
 * 任务由对话创建，因此始终回指 [agentId]，结果也回流到那条对话。
 *
 * [priority]、[categoryLabel]、[remindLabel]、[note] 是设计稿任务详情页要展示的字段，
 * 都是可选的——从对话里建出来的任务默认没有这些，由用户之后补。
 */
@Serializable
data class AgentTask(
    val id: String,
    val agentId: String,
    val title: String,
    val recurrence: Recurrence,
    val scheduleLabel: String,
    val nextRunLabel: String,
    val lastRunLabel: String? = null,
    val status: TaskStatus = TaskStatus.Active,
    val priority: TaskPriority? = null,
    val categoryLabel: String? = null,
    val remindLabel: String? = null,
    val note: String? = null,
)

/**
 * 任务草稿。用户用自然语言说"每天9点帮我汇总AI新闻"时，
 * 智能体识别出可托管的意图，把它解析成这张已填好参数的卡片，
 * 用户确认后才会真正创建任务。
 */
@Serializable
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
