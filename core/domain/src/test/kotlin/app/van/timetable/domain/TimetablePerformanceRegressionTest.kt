package app.van.timetable.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class TimetablePerformanceRegressionTest {
    private val course = Course(id = "c", name = "合成课程")
    private val morning = CourseSession(id = "morning", courseId = course.id, mode = TimeMode.CLOCK,
        weeks = setOf(1), start = "08:00", end = "09:40")
    private val schedule = Schedule(week1Monday = "2026-11-02", zone = "UTC",
        courses = listOf(course), sessions = listOf(morning))
    private fun instant(time: String) = Instant.parse("2026-11-02T${time}:00Z")

    @Test fun overlappingLessonsInvalidateAtEveryCompetingBoundary() {
        val priority = morning.copy(id = "priority", start = "08:45", end = "09:00", priority = 5)
        val mixed = schedule.copy(sessions = listOf(morning, priority))
        val first = CourseEngine.nextWindow(mixed, instant("08:01"))
        assertEquals("morning", first.occurrence!!.session.id)
        assertTrue(first.contains(instant("08:44")))
        assertFalse(first.contains(instant("08:00")))
        assertFalse(first.contains(instant("08:45")))
        val second = CourseEngine.nextWindow(mixed, first.until)
        assertEquals("priority", second.occurrence!!.session.id)
        assertEquals(instant("09:00"), second.until)
        assertEquals("morning", CourseEngine.next(mixed, second.until)!!.session.id)
    }

    @Test fun futureAndEmptyResultsExpireAtLocalMidnight() {
        val sunday = Instant.parse("2026-11-01T15:30:00Z")
        for (sessions in listOf(emptyList(), listOf(morning))) {
            val table = schedule.copy(zone = "Asia/Shanghai", sessions = sessions)
            val window = CourseEngine.nextWindow(table, sunday)
            assertEquals(sessions.firstOrNull()?.id, window.occurrence?.session?.id)
            assertEquals(Instant.parse("2026-11-01T16:00:00Z"), window.until)
        }
    }

    @Test fun cancelledClockLessonOnlyNeedsSpaceOnItsMakeupDate() {
        val lunch = morning.copy(start = "12:00", end = "13:00")
        val monday = "2026-11-02"
        val table = schedule.copy(sessions = listOf(lunch),
            sessionOverrides = listOf(SessionOverride(lunch.id, monday, cancelled = true)))
        assertTrue(GridTimeline.canCollapseGaps(table))
        val makeup = DayOverride("2027-07-01", monday)
        assertFalse(GridTimeline.canCollapseGaps(table, listOf(makeup)))
        assertTrue(GridTimeline.canCollapseGaps(table.copy(dayOverrides = listOf(makeup.copy(cancelled = true))), listOf(makeup)))
    }

    @Test fun replacedAndMovedClockTimesUseTheirEffectiveBoundaries() {
        val lunch = morning.copy(start = "12:00", end = "13:00")
        val moved = SessionOverride(lunch.id, "2026-11-02", "2027-01-02", start = "08:00", end = "09:40")
        val table = schedule.copy(sessions = listOf(lunch), sessionOverrides = listOf(moved))
        assertTrue(GridTimeline.canCollapseGaps(table))
        assertFalse(GridTimeline.canCollapseGaps(table.copy(sessionOverrides = listOf(moved.copy(start = null, end = null)))))
    }

    @Test fun seasonalRoutineTransitionsAgreeWithExhaustiveDayChecks() {
        val first = LocalDate.parse(schedule.week1Monday)
        val standard = schedule.routines.single()
        val late = TimeRoutine(id = "late", sections = standard.sections.map {
            it.copy(start = LocalTime.parse(it.start).plusMinutes(30).toString(),
                end = LocalTime.parse(it.end).plusMinutes(30).toString())
        })
        for (start in 0..6) for (day in 1..7) {
            val end = first.plusDays(start + 7L)
            val oneOff = end.plusWeeks(5)
            val section = morning.copy(id = "section", mode = TimeMode.SECTIONS, day = day, weeks = setOf(2))
            val rules = listOf(RoutineRule(late.id, first.plusDays(start.toLong()).toString(), end.toString()),
                RoutineRule(late.id, oneOff.toString(), oneOff.toString()))
            val global = listOf(DayOverride(oneOff.toString(), first.plusDays(day - 1L).toString()))
            val table = schedule.copy(maxWeeks = 3, sessions = listOf(section, morning.copy(day = day)),
                routines = listOf(standard, late), routineRules = rules)
            val dates = (0L until 21).map(first::plusDays) + oneOff
            val expected = dates.all { date ->
                val monday = date.minusDays(date.dayOfWeek.value - 1L)
                val axis = CourseEngine.routine(table, monday).sections
                val times = CourseEngine.timetableDay(table, date, global).map { it.start to it.end }.toMutableList()
                if (date.dayOfWeek.value == day) {
                    val routine = CourseEngine.routine(table, date).sections
                    times += LocalTime.parse(routine[0].start) to LocalTime.parse(routine[1].end)
                }
                times.all { (a, b) ->
                    axis.any { a >= LocalTime.parse(it.start) && a < LocalTime.parse(it.end) } &&
                        axis.any { b > LocalTime.parse(it.start) && b <= LocalTime.parse(it.end) }
                }
            }
            assertEquals("start=$start day=$day", expected, GridTimeline.canCollapseGaps(table, global))
        }
    }
}
