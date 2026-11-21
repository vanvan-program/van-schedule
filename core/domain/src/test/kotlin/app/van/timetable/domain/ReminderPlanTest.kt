// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 提醒时间的纯计算测试。
 *
 * 以前这些边界（提前量、冲突只提醒主课、跨周、假期、学期结束）只能上模拟器验证，
 * 现在可以直接用时间断言。
 */
class ReminderPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.parse("2026-09-07")

    private fun course(
        id: String,
        name: String = "示例课程",
        reminder: Boolean = true,
        lead: Int? = null,
    ) = Course(id = id, name = name, reminder = reminder, leadMinutes = lead)

    private fun clock(
        id: String,
        courseId: String,
        day: Int = 1,
        start: String = "08:00",
        end: String = "09:40",
        weeks: Set<Int> = (1..20).toSet(),
        priority: Int = 0,
    ) =
        CourseSession(
            id = id,
            courseId = courseId,
            day = day,
            weeks = weeks,
            mode = TimeMode.CLOCK,
            start = start,
            end = end,
            priority = priority,
        )

    private fun schedule(courses: List<Course>, sessions: List<CourseSession>) =
        Schedule(
            week1Monday = "2026-09-07",
            maxWeeks = 20,
            courses = courses,
            sessions = sessions,
        )

    @Test
    fun upcomingPrefersCourseLeadOverScheduleDefault() {
        val s = schedule(listOf(course("c1", lead = 30)), listOf(clock("s1", "c1")))
        val reminders = ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10)

        assertEquals(1, reminders.size)
        // 08:00 +08:00 提前 30 分钟 → 前一天 23:30Z
        assertEquals(Instant.parse("2026-09-06T23:30:00Z"), reminders[0].at)
        assertEquals("s1", reminders[0].occurrence.session.id)
    }

    @Test
    fun upcomingFallsBackToScheduleDefaultLead() {
        val s = schedule(listOf(course("c1")), listOf(clock("s1", "c1")))
        val reminders = ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10)

        assertEquals(Instant.parse("2026-09-06T23:50:00Z"), reminders[0].at)
    }

    @Test
    fun courseWithReminderOffIsExcluded() {
        val s = schedule(listOf(course("c1", reminder = false)), listOf(clock("s1", "c1")))
        assertTrue(ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10).isEmpty())
    }

    @Test
    fun conflictGroupOnlyRemindsTheMainCourse() {
        val s =
            schedule(
                listOf(course("c1", "低优先级"), course("c2", "高优先级")),
                listOf(
                    clock("s1", "c1", priority = 0),
                    clock("s2", "c2", priority = 5),
                ),
            )
        val reminders = ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10)

        assertEquals(1, reminders.size)
        assertEquals("高优先级", reminders[0].occurrence.course.name)
    }

    @Test
    fun remindersAreSortedByTriggerTime() {
        val s =
            schedule(
                listOf(course("c1"), course("c2")),
                listOf(
                    clock("s1", "c1", day = 2, start = "08:00", end = "09:40"),
                    clock("s2", "c2", day = 1, start = "14:00", end = "15:40"),
                ),
            )
        val reminders = ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10)

        assertEquals(2, reminders.size)
        assertTrue(reminders[0].at < reminders[1].at)
        assertEquals(1, reminders[0].occurrence.date.dayOfWeek.value)
    }

    @Test
    fun daysOutsideSemesterAreSkipped() {
        // 只有第 20 周有课，今天是第 1 周周一
        val s = schedule(listOf(course("c1")), listOf(clock("s1", "c1", weeks = setOf(20))))
        assertTrue(ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10, days = 3).isEmpty())
    }

    @Test
    fun holidaySuppressesReminders() {
        val s =
            schedule(listOf(course("c1")), listOf(clock("s1", "c1")))
                .copy(
                    holidays =
                        listOf(Holiday(id = "h", name = "国庆", from = "2026-09-07", through = "2026-09-07"))
                )
        assertTrue(ReminderPlan.upcoming(s, monday, zone, leadMinutes = 10, days = 1).isEmpty())
    }

    @Test
    fun nextWakePicksEarliestFutureInstant() {
        val now = Instant.parse("2026-09-07T00:00:00Z")
        val candidates =
            listOf(now.minusSeconds(60), now, now.plusSeconds(120), now.plusSeconds(30))

        assertEquals(now.plusSeconds(30), ReminderPlan.nextWake(now, candidates))
    }

    @Test
    fun nextWakeIsNullWhenEverythingIsPast() {
        val now = Instant.parse("2026-09-07T00:00:00Z")
        assertNull(ReminderPlan.nextWake(now, listOf(now, now.minusSeconds(1))))
        assertNull(ReminderPlan.nextWake(now, emptyList()))
    }
}
