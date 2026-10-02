package com.starringbox.staragent.core.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
 * 从时间描述里取"HH:MM"。取不到就退回 09:00——
 * 任务的时间是自由文本，用户写"每天晚上十点"这种也认不出来，但至少不能因此排不上期。
 */
private fun parseTimeOfDay(label: String): LocalTime {
    val match = Regex("""(\d{1,2}):(\d{2})""").find(label) ?: return LocalTime(9, 0)
    val hour = match.groupValues[1].toIntOrNull() ?: return LocalTime(9, 0)
    val minute = match.groupValues[2].toIntOrNull() ?: return LocalTime(9, 0)
    if (hour !in 0..23 || minute !in 0..59) return LocalTime(9, 0)
    return LocalTime(hour, minute)
}

/** 从"每周三"「星期三」里取星期几，写得不明确就当周一。 */
private fun parseWeekday(label: String): DayOfWeek {
    val match = Regex("""(?:周|星期|礼拜)([一二三四五六日天])""").find(label) ?: return DayOfWeek.MONDAY
    return when (match.groupValues[1]) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}

/** 从"每月 15 日"里取几号，写得不明确就当 1 号。 */
private fun parseDayOfMonth(label: String): Int {
    val match = Regex("""(\d{1,2})\s*[日号]""").find(label) ?: return 1
    val day = match.groupValues[1].toIntOrNull() ?: return 1
    return day.coerceIn(1, 31)
}

/** 某个月有多少天。用"下月 1 号 − 本月 1 号"算，省得维护一张各月天数表。 */
private fun daysInMonth(year: Int, month: Int): Int {
    val start = LocalDate(year, Month(month), 1)
    val next = if (month == Month.DECEMBER.number) {
        LocalDate(year + 1, Month.JANUARY, 1)
    } else {
        LocalDate(year, Month(month + 1), 1)
    }
    return (next.toEpochDays() - start.toEpochDays()).toInt()
}

private fun LocalDate.shiftDays(days: Long): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)

/**
 * 下一次该跑的时刻。
 *
 * 条件触发没有固定时刻，返回 null。其余类型按**本地日历**算——时区和夏令时交给
 * kotlinx-datetime，自己拿毫秒加减会在换季那天差一小时。
 */
fun nextRunAtFor(recurrence: Recurrence, scheduleLabel: String, now: Instant, zone: TimeZone): Instant? {
    if (recurrence == Recurrence.Conditional) return null

    val time = parseTimeOfDay(scheduleLabel)
    val today = now.toLocalDateTime(zone).date

    fun at(date: LocalDate): Instant =
        LocalDateTime(date.year, date.month, date.day, time.hour, time.minute).toInstant(zone)

    return when (recurrence) {
        // 一次性任务：今天这个点还没过就是今天，过了顺延到明天
        Recurrence.Once, Recurrence.Daily -> {
            val todayAt = at(today)
            if (todayAt > now) todayAt else at(today.shiftDays(1))
        }

        Recurrence.Weekly -> {
            val target = parseWeekday(scheduleLabel)
            var date = today
            var candidate = at(date)
            var guard = 0
            while (guard < 8 && (date.dayOfWeek != target || candidate <= now)) {
                date = date.shiftDays(1)
                candidate = at(date)
                guard++
            }
            candidate
        }

        Recurrence.Monthly -> {
            val day = parseDayOfMonth(scheduleLabel)
            var year = today.year
            var month = today.month.number
            var found: Instant? = null
            var guard = 0
            // 最多往后翻 14 个月，一定命中一次
            while (found == null && guard < 14) {
                // "每月 31 日"遇到 2 月就落在月末，而不是把这个月整个跳过去
                val candidate = at(LocalDate(year, Month(month), day.coerceAtMost(daysInMonth(year, month))))
                if (candidate > now) found = candidate
                if (month == Month.DECEMBER.number) {
                    year += 1
                    month = 1
                } else {
                    month += 1
                }
                guard++
            }
            found
        }

        Recurrence.Conditional -> null
    }
}

private fun two(value: Int): String = if (value < 10) "0$value" else "$value"

private fun weekdayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    else -> "日"
}

/**
 * 把下一次执行时刻讲成人话：「今天 09:00」「明天 09:00」「周三 09:00」「9 月 1 日 09:00」。
 *
 * 界面上显示的是它；真正用来判断该不该跑的是 [AgentTask.nextRunAt]，那个能跟当前时间比。
 */
fun describeNextRun(at: Instant?, recurrence: Recurrence, now: Instant, zone: TimeZone): String {
    if (recurrence == Recurrence.Conditional) return "满足条件时触发"
    if (at == null) return "未排期"

    val target = at.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val days = target.date.toEpochDays() - today.toEpochDays()
    val clock = "${two(target.hour)}:${two(target.minute)}"

    return when {
        days <= 0L -> "今天 $clock"
        days == 1L -> "明天 $clock"
        days < 7L -> "周${weekdayName(target.dayOfWeek)} $clock"
        else -> "${target.month.number} 月 ${target.day} 日 $clock"
    }
}

/** 把一个**已经过去**的时刻说成"今天 09:02"「昨天 21:15」这种，给"上次执行"用。 */
fun describeMoment(at: Instant, now: Instant, zone: TimeZone): String {
    val target = at.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val days = target.date.toEpochDays() - today.toEpochDays()
    val clock = "${two(target.hour)}:${two(target.minute)}"
    return when (days) {
        0L -> "今天 $clock"
        -1L -> "昨天 $clock"
        1L -> "明天 $clock"
        else -> "${target.month.number} 月 ${target.day} 日 $clock"
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
    /** 最近一次执行的正文，通知点击后在任务承接页显示。 */
    val lastRunResult: String? = null,
    /** 最近一次生成的文件产物，存在时通知直接打开产物预览。 */
    val lastArtifactId: String? = null,
    /**
     * 下一次该跑的时刻（epoch 毫秒）。
     *
     * [nextRunLabel] 只是给人看的文字，没法跟当前时间比——调度要靠这个。
     * 存 Long 而不是 Instant，是为了 JSON 里就是一个普通数字，不需要自定义序列化器。
     * null 表示还没排期：条件触发的任务、或者刚从旧存档读出来还没算过的。
     */
    val nextRunAt: Long? = null,
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
