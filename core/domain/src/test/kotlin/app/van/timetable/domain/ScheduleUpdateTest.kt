// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleUpdateTest {
    private val monday = "2026-09-07"
    private val originalCourse = Course(id = "local", name = "高等数学", note = "复习例题", weekNotes = mapOf(2 to "交作业"))
    private val importedCourse = Course(id = "imported", name = "高等数学")
    private val local = CourseSession(id = "local-class", courseId = originalCourse.id, weeks = setOf(1, 2), place = "A101")
    private val incoming = local.copy(id = "imported-class", courseId = importedCourse.id)
    private val original = Schedule(id = "original", week1Monday = monday, courses = listOf(originalCourse), sessions = listOf(local))
    private val imported = Schedule(week1Monday = "2026-09-14", courses = listOf(importedCourse), sessions = listOf(incoming))

    @Test fun showsAddedRemovedAndChangedTimesWhileKeepingTheTargetAndNotes() {
        val removed = Course(id = "removed", name = "历史")
        val added = Course(id = "added", name = "体育")
        val target = original.copy(
            courses = original.courses + removed,
            sessions = original.sessions + local.copy(id = "history", courseId = removed.id, day = 4),
            holidays = listOf(Holiday(from = "2026-09-19", through = "2026-09-20")),
            dayOverrides = listOf(DayOverride("2026-09-12", monday)),
        )
        val source = imported.copy(
            courses = imported.courses + added,
            sessions = listOf(incoming.copy(day = 3), incoming.copy(id = "sport", courseId = added.id)),
        )
        val plan = ScheduleUpdate.plan(target, source)
        assertEquals(setOf(ScheduleUpdate.Change.ADDED, ScheduleUpdate.Change.REMOVED, ScheduleUpdate.Change.TIME),
            plan.differences.map { it.change }.toSet())
        val result = plan.preview(emptySet()).schedule
        assertEquals(target.id, result.id)
        assertEquals(target.name, result.name)
        assertEquals(monday, result.week1Monday)
        assertEquals(target.holidays, result.holidays)
        assertEquals(target.dayOverrides, result.dayOverrides)
        assertEquals(target.routines, result.routines)
        assertEquals(originalCourse, result.courses.first())
        assertEquals(3, result.sessions.first { it.courseId == originalCourse.id }.day)
    }

    @Test fun splittingWeeksKeepsTheAdjustmentForItsOriginalDate() {
        val adjustment = SessionOverride(local.id, "2026-09-14", targetDate = "2026-09-18", place = "B203")
        val source = imported.copy(sessions = listOf(
            incoming.copy(weeks = setOf(1)),
            incoming.copy(id = "split", weeks = setOf(2), place = "A102"),
        ))
        val plan = ScheduleUpdate.plan(original.copy(sessionOverrides = listOf(adjustment)), source)
        assertEquals(ScheduleUpdate.Change.DETAILS, plan.differences.single().change)
        assertTrue(plan.differences.single().issues.isEmpty())
        val result = plan.preview(emptySet()).schedule
        assertEquals(adjustment.copy(sessionId = "split"), result.sessionOverrides.single())
        assertTrue(CourseEngine.resolveDay(result, LocalDate.parse(adjustment.originalDate)).isEmpty())
        assertEquals("B203", CourseEngine.resolveDay(result, LocalDate.parse(adjustment.targetDate)).single().place)
    }

    @Test fun timeChangeOnSameDayRetainsTheAdjustmentWithAWarning() {
        val adjustment = SessionOverride(local.id, monday, cancelled = true)
        val plan = ScheduleUpdate.plan(original.copy(sessionOverrides = listOf(adjustment)),
            imported.copy(sessions = listOf(incoming.copy(startSection = 3, endSection = 4))))
        assertTrue(plan.differences.single().issues.single().contains("仍会沿用"))
        val result = plan.preview(emptySet()).schedule
        assertEquals(listOf(adjustment.copy(sessionId = incoming.id)), result.sessionOverrides)
        assertTrue(CourseEngine.resolveDay(result, LocalDate.parse(monday)).isEmpty())
    }

    @Test fun missingOriginalDayRequiresAChoiceRatherThanMovingTheAdjustmentToAnotherDate() {
        val adjustment = SessionOverride(local.id, monday, targetDate = "2026-09-10")
        val before = original.copy(sessionOverrides = listOf(adjustment))
        val plan = ScheduleUpdate.plan(before, imported.copy(sessions = listOf(incoming.copy(day = 3))))
        val diff = plan.differences.single()
        assertTrue(diff.issues.single().contains("移除这笔调整"))
        assertTrue(plan.preview(emptySet()).schedule.sessionOverrides.isEmpty())
        val kept = plan.preview(setOf(diff.id)).schedule
        assertEquals(original.sessions, kept.sessions)
        assertEquals(before.sessionOverrides, kept.sessionOverrides)
    }

    @Test fun sameNameAmbiguityAndRemovedNotesRequireReview() {
        val twin = originalCourse.copy(id = "twin", note = "另一个班")
        val ambiguous = ScheduleUpdate.plan(original.copy(courses = listOf(originalCourse, twin)), imported)
        val diff = ambiguous.differences.single()
        assertTrue(diff.issues.single().contains("同名课程"))
        assertEquals(listOf(originalCourse, twin), ambiguous.preview(setOf(diff.id)).schedule.courses)
        val removed = ScheduleUpdate.plan(original, imported.copy(courses = emptyList(), sessions = emptyList()))
        assertTrue(removed.differences.single().issues.single().contains("备注"))
    }

    @Test fun exactMatchesKeepIdsAndDoNotFlagExistingOverlaps() {
        val overlapping = Course(id = "physics", name = "物理")
        val oldSlot = local.copy(id = "physics-local", courseId = overlapping.id)
        val originalWithOverlap = original.copy(courses = original.courses + overlapping, sessions = original.sessions + oldSlot)
        val plan = ScheduleUpdate.plan(originalWithOverlap, imported.copy(
            courses = imported.courses + overlapping,
            sessions = listOf(incoming.copy(teacher = "赵老师"), oldSlot.copy(id = "physics-new")),
        ))
        val preview = plan.preview(emptySet())
        assertTrue(preview.newOverlaps.isEmpty())
        assertEquals(listOf(local.id, oldSlot.id), preview.schedule.sessions.map { it.id })
    }

    @Test fun findsNewTimeConflictsOnMakeupDaysAndRecomputesWhenSkippingTheNewCourse() {
        val added = Course(id = "new", name = "物理")
        val newSlot = incoming.copy(id = "new-slot", courseId = added.id, weeks = setOf(1))
        val plan = ScheduleUpdate.plan(original, imported.copy(courses = imported.courses + added,
            sessions = imported.sessions + newSlot), listOf(DayOverride("2026-09-12", monday)))
        assertEquals(listOf(monday, "2026-09-12"), plan.preview(emptySet()).newOverlaps.map { it.first.date.toString() })
        assertTrue(plan.preview(setOf(plan.differences.single().id)).newOverlaps.isEmpty())
    }

    @Test fun offersToKeepAOneOffLessonAndAddsOnlyNeededWeeksAndSections() {
        val oneOff = local.copy(id = "one-off", date = "2026-09-15", day = 2)
        val firstTwo = original.routines.first().copy(sections = original.routines.first().sections.take(2))
        val plan = ScheduleUpdate.plan(original.copy(maxWeeks = 2, routines = listOf(firstTwo), sessions = listOf(local, oneOff)),
            imported.copy(sessions = listOf(incoming.copy(startSection = 3, endSection = 4, weeks = setOf(3)))))
        val diff = plan.differences.single()
        assertTrue(diff.issues.single().contains("单次课程"))
        assertTrue(plan.preview(setOf(diff.id)).schedule.sessions.any { it.id == oneOff.id })
        val result = plan.preview(emptySet()).schedule
        assertEquals(3, result.maxWeeks)
        assertEquals(4, result.routines.single().sections.size)
        assertEquals(firstTwo.sections, result.routines.single().sections.take(2))
    }
}
