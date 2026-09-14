package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.Recurrence
import com.starringbox.staragent.core.model.describeNextRun
import com.starringbox.staragent.core.model.nextRunAtFor
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * 「下一次什么时候跑」的算法。
 *
 * 以前任务只有一个给人看的字符串（"明天 09:00"），没法跟当前时间比——所以任务永远不会执行。
 * 现在要算出真实时刻，这几个用例把各类时间的边界钉住。
 */
class TaskScheduleTest {

    private val zone = TimeZone.UTC

    private fun utc(text: String): Instant = Instant.parse(text)

    @Test
    fun dailyStaysTodayWhileTheTimeIsStillAhead() {
        val now = utc("2026-09-14T08:00:00Z")
        assertEquals(utc("2026-09-14T09:00:00Z"), nextRunAtFor(Recurrence.Daily, "每天 09:00", now, zone))
    }

    @Test
    fun dailyRollsToTomorrowOnceTheTimeHasPassed() {
        val now = utc("2026-09-14T10:00:00Z")
        assertEquals(utc("2026-09-15T09:00:00Z"), nextRunAtFor(Recurrence.Daily, "每天 09:00", now, zone))
    }

    @Test
    fun weeklyLandsOnTheNamedWeekday() {
        // 2026-09-14 是周一
        val now = utc("2026-09-14T10:00:00Z")
        assertEquals(utc("2026-09-16T09:00:00Z"), nextRunAtFor(Recurrence.Weekly, "每周三 09:00", now, zone))
    }

    @Test
    fun weeklyPushesAWholeWeekWhenTodayIsTheDayButTheTimeHasPassed() {
        val now = utc("2026-09-14T10:00:00Z") // 周一，但 09:00 已经过了
        assertEquals(utc("2026-09-21T09:00:00Z"), nextRunAtFor(Recurrence.Weekly, "每周一 09:00", now, zone))
    }

    @Test
    fun monthlyClampsToTheLastDayOfAShortMonth() {
        val now = utc("2026-01-31T10:00:00Z")
        // 1 月 31 日已过，下一期落在 2 月；2 月没有 31 号，取月末而不是把这一个月跳过去
        assertEquals(utc("2026-02-28T09:00:00Z"), nextRunAtFor(Recurrence.Monthly, "每月 31 日 09:00", now, zone))
    }

    @Test
    fun conditionalHasNoFixedMoment() {
        assertNull(
            nextRunAtFor(Recurrence.Conditional, "当连接 Wi-Fi 时", utc("2026-09-14T08:00:00Z"), zone)
        )
    }

    @Test
    fun aTimeWeCannotReadFallsBackToNineRatherThanFailing() {
        val now = utc("2026-09-14T08:00:00Z")
        assertEquals(utc("2026-09-14T09:00:00Z"), nextRunAtFor(Recurrence.Daily, "每天晚上", now, zone))
    }

    @Test
    fun theLabelAgreesWithTheMoment() {
        val now = utc("2026-09-14T08:00:00Z")

        val today = nextRunAtFor(Recurrence.Daily, "每天 09:00", now, zone)
        assertEquals("今天 09:00", describeNextRun(today, Recurrence.Daily, now, zone))

        val tomorrow = nextRunAtFor(Recurrence.Daily, "每天 07:30", now, zone)
        assertEquals("明天 07:30", describeNextRun(tomorrow, Recurrence.Daily, now, zone))
    }
}
