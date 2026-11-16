package app.van.timetable.domain

import org.junit.Assert.*
import org.junit.Test

class CourseGroupsTest {
    private val root = Course(id = "main", name = "数字图像处理")
    private val lab = Course(id = "lab", name = "数字图像处理 · 实验课", parentCourseId = root.id, kind = "实验课")
    private val exam = Course(id = "exam", name = "数字图像处理 · 期末考试", parentCourseId = root.id, kind = "期末考试")
    private fun schedule(vararg c: Course) = Schedule(week1Monday = "2026-10-26", courses = c.toList())

    @Test fun childrenBeforeParentStillFormOneGroupWithParentFirst() {
        val s = schedule(lab, exam, root)
        val group = s.courseGroups().single()
        assertEquals(root, group.primary)
        assertEquals(listOf(root, lab, exam), group.members)
        assertEquals(listOf("正课", "实验课", "期末考试"), group.members.map { it.groupSectionLabel() })
        assertEquals(listOf(lab, exam, root), s.courses)
    }

    @Test fun similarNamesDoNotMergeAndOrphansStayVisible() {
        val unrelated = root.copy(id = "other")
        val orphan = lab.copy(parentCourseId = "deleted")
        assertEquals(listOf(root, unrelated, orphan), schedule(root, unrelated, orphan).courseGroups().map { it.primary })
    }

    @Test fun nestedRelationshipsAndCyclesNeverHideOrDuplicateCourses() {
        val nested = exam.copy(parentCourseId = lab.id)
        assertEquals(setOf(root.id, lab.id, exam.id), schedule(root, lab, nested).courseGroups().single().members.map { it.id }.toSet())
        val cyclic = schedule(root.copy(parentCourseId = lab.id), lab, exam)
        val visible = cyclic.courseGroups().flatMap { it.members }.map { it.id }
        assertEquals(cyclic.courses.map { it.id }.sorted(), visible.sorted())
    }

    @Test fun groupingLeavesSessionDataAndBackupRoundTripUnchanged() {
        val s = schedule(root, lab, exam).copy(sessions = listOf(
            CourseSession(courseId = root.id),
            CourseSession(courseId = lab.id, date = "2026-10-31", weeks = emptySet()),
            CourseSession(courseId = exam.id, mode = TimeMode.CLOCK, date = "2026-11-01", weeks = emptySet()),
        ))
        val before = Codec.backup(Backup(schedules = listOf(s)))
        s.courseGroups()
        assertEquals(before, Codec.backup(Backup(schedules = listOf(s))))
        assertEquals(s.courseGroups(), Codec.restore(before).schedules.single().courseGroups())
    }
}
