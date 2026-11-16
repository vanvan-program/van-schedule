// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexioParityDomainTest {
    private fun baseSchedule(): Schedule {
        val c1 = Course(id = "c1", name = "高等数学2", weekNotes = mapOf(1 to "带计算器"))
        val c2 = Course(id = "c2", name = "高等数学10")
        val c3 = Course(id = "c3", name = "操作系统")
        val s1 =
            CourseSession(
                id = "s1",
                courseId = "c1",
                day = 1,
                weeks = setOf(1, 2, 3),
                startSection = 1,
                endSection = 2,
                place = "A101",
            )
        val s2 =
            CourseSession(
                id = "s2",
                courseId = "c2",
                day = 3,
                weeks = setOf(2),
                startSection = 3,
                endSection = 4,
                place = "B202",
            )
        return Schedule(
            id = "sch1",
            name = "主课表",
            week1Monday = "2026-09-07",
            maxWeeks = 18,
            courses = listOf(c1, c2, c3),
            sessions = listOf(s1, s2),
        )
    }

    @Test
    fun `natural Chinese and number sorting orders courses properly`() {
        val sorted =
            NaturalCourseSort.sortCourses(
                listOf(
                    Course(id = "1", name = "高等数学10"),
                    Course(id = "2", name = "操作系统"),
                    Course(id = "3", name = "高等数学2"),
                    Course(id = "4", name = "大学英语"),
                )
            )
        assertEquals(
            listOf("操作系统", "大学英语", "高等数学2", "高等数学10"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `weekend auto skip jumps to next week only when Sat and Sun are empty`() {
        val s = baseSchedule()
        // 2026-09-12 is Saturday of week 1, weekend is empty -> week 2
        assertEquals(2, CourseEngine.effectiveDisplayWeek(s, LocalDate.parse("2026-09-12"), true))
        assertEquals(1, CourseEngine.effectiveDisplayWeek(s, LocalDate.parse("2026-09-12"), false))

        // Add Sunday course on week 1 -> stays on week 1
        val withSun =
            s.copy(
                sessions =
                    s.sessions +
                        CourseSession(
                            id = "s3",
                            courseId = "c3",
                            day = 7,
                            weeks = setOf(1),
                            startSection = 1,
                            endSection = 2,
                        )
            )
        assertEquals(1, CourseEngine.effectiveDisplayWeek(withSun, LocalDate.parse("2026-09-12"), true))
    }

    @Test
    fun `activeRoutineId and custom section labels work and validate`() {
        val r1 = TimeRoutine(id = "r1", name = "夏季", sections = defaultSections())
        val r2 =
            TimeRoutine(
                id = "r2",
                name = "冬季",
                sections =
                    defaultSections().map {
                        when (it.number) {
                            1 -> it.copy(start = "08:30", end = "09:15", label = "早一")
                            2 -> it.copy(start = "09:20", end = "09:55")
                            else -> it
                        }
                    },
            )
        val s = baseSchedule().copy(routines = listOf(r1, r2), activeRoutineId = "r2")
        Validation.requireValid(s)
        val resolved = CourseEngine.routine(s, LocalDate.parse("2026-09-07"))
        assertEquals("r2", resolved.id)
        assertEquals("早一", resolved.sections.first().label)
    }

    @Test
    fun `globalDayOverrides follow absolute dates across schedules`() {
        val s = baseSchedule()
        val global = listOf(DayOverride(date = "2026-09-12", sourceDate = "2026-09-07"))
        val day = CourseEngine.resolveDay(s, LocalDate.parse("2026-09-12"), global)
        assertEquals(1, day.size)
        assertEquals("c1", day.first().course.id)
        assertTrue(day.first().adjusted)
    }

    @Test
    fun `move overwrite and swap sessions across weeks preserve other weeks`() {
        val s = baseSchedule()
        // Move s1 week 1 to week 2 day 2 sections 5..6 -> splits week 1 out (and keeps week 2,3 in s1)
        val moved =
            CourseEngine.moveSessionForWeek(
                s,
                sessionId = "s1",
                fromWeek = 1,
                targetWeek = 2,
                targetDay = 2,
                targetStartSection = 5,
                targetEndSection = 6,
            )
        Validation.requireValid(moved)
        assertEquals(setOf(2, 3), moved.sessions.first { it.id == "s1" }.weeks)
        assertTrue(
            moved.sessions.any {
                it.id != "s1" && it.day == 2 && it.startSection == 5 && it.weeks == setOf(2)
            }
        )

        // Overwrite s2 at week 2 day 3 sections 3..4 with s1 week 2
        val overwritten =
            CourseEngine.overwriteSessionForWeek(
                s,
                sessionId = "s1",
                fromWeek = 2,
                targetWeek = 2,
                targetDay = 3,
                targetStartSection = 3,
                targetEndSection = 4,
            )
        Validation.requireValid(overwritten)
        assertNull(overwritten.sessions.firstOrNull { it.id == "s2" })
        assertTrue(
            overwritten.sessions.any {
                it.courseId == "c1" && it.day == 3 && it.startSection == 3 && it.weeks == setOf(2)
            }
        )

        // Swap s1 (week 2, day 1, 1..2) with s2 (week 2, day 3, 3..4)
        val swapped =
            CourseEngine.swapSessionsForWeek(
                s,
                sourceSessionId = "s1",
                sourceWeek = 2,
                targetSessionId = "s2",
                targetWeek = 2,
            )
        Validation.requireValid(swapped)
        assertTrue(
            swapped.sessions.any {
                it.courseId == "c1" && it.day == 3 && it.startSection == 3 && it.weeks == setOf(2)
            }
        )
        assertTrue(
            swapped.sessions.any {
                it.courseId == "c2" && it.day == 1 && it.startSection == 1 && it.weeks == setOf(2)
            }
        )
    }

    @Test
    fun `startNewSemester reuses routines and clears courses and overrides`() {
        val s =
            baseSchedule()
                .copy(
                    dayOverrides = listOf(DayOverride("2026-09-10", cancelled = true)),
                    holidays = listOf(Holiday(name = "国庆", from = "2026-10-01", through = "2026-10-07")),
                )
        val next = CourseEngine.startNewSemester(s, "2027-02-22", 19, "春季课表")
        Validation.requireValid(next)
        assertEquals("春季课表", next.name)
        assertEquals("2027-02-22", next.week1Monday)
        assertEquals(19, next.maxWeeks)
        assertTrue(next.courses.isEmpty())
        assertTrue(next.sessions.isEmpty())
        assertTrue(next.dayOverrides.isEmpty())
        assertTrue(next.holidays.isEmpty())
        assertEquals(s.routines, next.routines)
    }
}
