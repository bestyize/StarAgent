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
 * 按重复方式和时间描述算出"下次执行"的文案。
 *
 * 时间是从描述里取的——任务的时间本来就只是一个自由文本
 * （"每天 09:00"、"当连接 Wi-Fi 时"），取不到具体时刻就退回 09:00。
 *
 * 放在模型层是为了让编辑页能实时预览：改了类型或时间立刻看到下次什么时候跑，
 * 而不用先保存再回列表里对。
 */
fun nextRunLabelFor(recurrence: Recurrence, scheduleLabel: String): String {
    val time = Regex("""(\d{1,2}:\d{2})""")
        .find(scheduleLabel)
        ?.groupValues
        ?.get(1)
        ?.let { if (it.length == 4) "0$it" else it }
        ?: "09:00"
    return when (recurrence) {
        Recurrence.Once -> "待触发"
        Recurrence.Daily -> "明天 $time"
        Recurrence.Weekly -> "下周一 $time"
        Recurrence.Monthly -> "下月 1 日 $time"
        Recurrence.Conditional -> "满足条件时触发"
    }
}

/** 每种类型新建时的默认时间描述，也是输入框的占位文案。 */
fun defaultScheduleFor(recurrence: Recurrence): String = when (recurrence) {
    Recurrence.Once -> "今天 10:00"
    Recurrence.Daily -> "每天 09:00"
    Recurrence.Weekly -> "每周一 09:00"
    Recurrence.Monthly -> "每月 1 日 09:00"
    Recurrence.Conditional -> "当连接 Wi-Fi 时"
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
