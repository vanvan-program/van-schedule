// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

object ScheduleUpdate {
    enum class Change { ADDED, REMOVED, TIME, DETAILS }

    data class Courses(
        val courses: List<Course>,
        val sessions: List<CourseSession>,
        val adjustments: List<SessionOverride> = emptyList(),
    )

    data class Difference(
        val id: Int,
        val before: Courses,
        val imported: Courses,
        val change: Change,
        val issues: List<String>,
    )

    data class Overlap(val first: Occurrence, val second: Occurrence)
    data class Preview(val schedule: Schedule, val newOverlaps: List<Overlap>)

    class Plan internal constructor(
        val original: Schedule,
        private val incoming: Schedule,
        val globalDays: List<DayOverride>,
        val differences: List<Difference>,
        private val unchanged: List<Courses>,
    ) {
        fun preview(keepOriginal: Set<Int>): Preview {
            val groups = unchanged + differences.map { if (it.id in keepOriginal) it.before else it.imported }
            val oldSessionOrder = original.sessions.mapIndexed { index, session -> session.id to index }.toMap()
            val oldCourseOrder = original.courses.mapIndexed { index, course -> course.id to index }.toMap()
            val sessions = groups.flatMap { it.sessions }.sortedBy { oldSessionOrder[it.id] ?: Int.MAX_VALUE }
            val courses = groups.flatMap { it.courses }.sortedBy { oldCourseOrder[it.id] ?: Int.MAX_VALUE }
            val courseIds = courses.mapTo(hashSetOf()) { it.id }
            val maxSection = sessions.filter { it.mode == TimeMode.SECTIONS }.maxOfOrNull { it.endSection } ?: 0
            val importedSections = incoming.routines.first().sections.associateBy { it.number }
            val routines = original.routines.map { routine ->
                val added = (routine.sections.size + 1..maxSection).map { number ->
                    importedSections[number] ?: error("缺少第${number}节的作息时间")
                }
                if (added.isEmpty()) routine else routine.copy(sections = routine.sections + added)
            }
            val maxSessionWeek = sessions.asSequence().filter { it.date == null }.flatMap { it.weeks }.maxOrNull() ?: 0
            val maxNoteWeek = courses.asSequence().flatMap { it.weekNotes.keys }.maxOrNull() ?: 0
            val result = original.copy(
                school = incoming.school.ifBlank { original.school },
                source = incoming.source,
                importedAt = incoming.importedAt,
                maxWeeks = maxOf(original.maxWeeks, maxSessionWeek, maxNoteWeek),
                routines = routines,
                courses = courses.map { course ->
                    course.copy(parentCourseId = course.parentCourseId?.takeIf { it in courseIds && it != course.id })
                },
                sessions = sessions,
                sessionOverrides = groups.flatMap { it.adjustments },
            )
            Validation.requireValid(result)
            val overlaps = findOverlaps(result, globalDays)
            if (overlaps.isEmpty()) return Preview(result, emptyList())
            val previous = findOverlaps(original, globalDays).groupingBy(::overlapKey).eachCount().toMutableMap()
            val added = overlaps.filter { overlap ->
                val key = overlapKey(overlap)
                val count = previous[key] ?: 0
                if (count > 0) previous[key] = count - 1
                count == 0
            }
            return Preview(result, added)
        }
    }

    fun plan(original: Schedule, incoming: Schedule, globalDays: List<DayOverride> = emptyList()): Plan {
        val pairs = matchCourses(original.courses, incoming.courses)
        val courseIds = pairs.flatMap { (before, after) ->
            val match = before.singleOrNull()?.takeIf { after.size == 1 }
            after.map { it.id to (match?.id ?: it.id) }
        }.toMap()
        val oldSessionIds = original.sessions.mapTo(hashSetOf()) { it.id }
        val beforeSessions = original.sessions.groupBy { it.courseId }
        val incomingSessions = incoming.sessions.groupBy { it.courseId }
        val oldAdjustments = original.sessionOverrides.groupBy { it.sessionId }
        val differences = mutableListOf<Difference>()
        val unchanged = mutableListOf<Courses>()
        pairs.forEachIndexed { index, (oldCourses, newCourses) ->
            val oldSessions = oldCourses.flatMap { beforeSessions[it.id].orEmpty() }
            val before = Courses(oldCourses, oldSessions, oldSessions.flatMap { oldAdjustments[it.id].orEmpty() })
            val old = oldCourses.singleOrNull()?.takeIf { newCourses.size == 1 }
            val newSessions = mergeSessions(oldSessions.takeIf { old != null }.orEmpty(),
                newCourses.flatMap { incomingSessions[it.id].orEmpty() }, courseIds, oldSessionIds)
            val proposed = Courses(newCourses.map { course ->
                val parent = course.parentCourseId?.let(courseIds::get)
                if (old == null) course.copy(id = courseIds.getValue(course.id), parentCourseId = parent)
                else old.copy(name = course.name, credit = old.credit.ifBlank { course.credit },
                    note = old.note.ifBlank { course.note }, weekNotes = course.weekNotes + old.weekNotes,
                    parentCourseId = old.parentCourseId ?: parent)
            }, newSessions)

            val change = when {
                oldCourses.isEmpty() -> Change.ADDED
                newCourses.isEmpty() -> Change.REMOVED
                old == null || slots(oldSessions, false) != slots(newSessions, false) -> Change.TIME
                before.courses != proposed.courses || slots(oldSessions, true) != slots(newSessions, true) -> Change.DETAILS
                else -> null
            }
            if (change == null) {
                unchanged += before
                return@forEachIndexed
            }
            val issues = mutableListOf<String>()
            if (old == null && oldCourses.isNotEmpty() && newCourses.isNotEmpty()) {
                issues += "存在同名课程，无法确定备注和调课分别属于哪门课。"
            }
            if (newCourses.isEmpty() && oldCourses.any { it.note.isNotBlank() || it.weekNotes.isNotEmpty() }) {
                issues += "采用导入安排会移除这些课程及其备注。"
            }
            val removedDatedSessions = oldSessions.filter { session ->
                session.date != null && newSessions.none { it.placement() == session.placement() }
            }
            removedDatedSessions.forEach { issues += "原有 ${it.date} 的单次课程在导入安排中已变更。" }
            val adjustments = remapAdjustments(original, incoming, before, proposed, old != null, globalDays, issues)
            differences += Difference(index, before, proposed.copy(adjustments = adjustments), change, issues)
        }
        return Plan(original, incoming, globalDays, differences, unchanged)
    }

    private fun matchCourses(before: List<Course>, imported: List<Course>): List<Pair<List<Course>, List<Course>>> {
        val byId = imported.associateBy { it.id }
        val ids = before.mapTo(hashSetOf()) { it.id }.intersect(byId.keys)
        val remainingBefore = before.filterNot { it.id in ids }.groupBy(::courseKey)
        val remainingImported = imported.filterNot { it.id in ids }.groupBy(::courseKey)
        return before.filter { it.id in ids }.map { listOf(it) to listOf(byId.getValue(it.id)) } +
            (remainingBefore.keys + remainingImported.keys).map { key ->
                remainingBefore[key].orEmpty() to remainingImported[key].orEmpty()
            }
    }

    private val spaces = Regex("\\s+")

    private fun courseKey(course: Course): Pair<String, String> =
        Normalizer.normalize(course.name, Normalizer.Form.NFKC).trim().replace(spaces, " ")
            .lowercase(Locale.ROOT) to course.kind

    private data class Time(val mode: TimeMode, val start: String = "", val end: String = "")
    private data class Placement(val date: String?, val day: Int?, val time: Time)
    private data class Slot(val placement: Placement, val teacher: String, val place: String)

    private fun CourseSession.time(): Time = when (mode) {
        TimeMode.SECTIONS -> Time(mode, startSection.toString(), endSection.toString())
        TimeMode.CLOCK -> Time(mode, LocalTime.parse(start).toString(), LocalTime.parse(end).toString())
        TimeMode.UNTIMED -> Time(mode)
    }

    private fun CourseSession.placement() = Placement(date, day.takeIf { date == null }, time())

    private fun slots(sessions: List<CourseSession>, details: Boolean): Map<Slot, Set<Int>> =
        sessions.groupBy { Slot(it.placement(), it.teacher.takeIf { details }.orEmpty(), it.place.takeIf { details }.orEmpty()) }
            .mapValues { (key, values) -> if (key.placement.date != null) emptySet() else values.flatMap { it.weeks }.toSet() }

    private fun mergeSessions(
        before: List<CourseSession>,
        incoming: List<CourseSession>,
        courseIds: Map<String, String>,
        reserved: Set<String>,
    ): List<CourseSession> {
        val oldById = before.associateBy { it.id }
        val incomingIds = incoming.mapTo(hashSetOf()) { it.id }
        val available = before.filterNot { it.id in incomingIds }.groupBy { it.placement() }
        val counts = incoming.groupingBy { it.placement() }.eachCount()
        return incoming.map { session ->
            val previous = oldById[session.id] ?: available[session.placement()]?.singleOrNull()
                ?.takeIf { counts[session.placement()] == 1 }
            session.copy(
                id = previous?.id ?: if (session.id in reserved) newId() else session.id,
                courseId = courseIds.getValue(session.courseId),
                teacher = session.teacher.ifBlank { previous?.teacher.orEmpty() },
                place = session.place.ifBlank { previous?.place.orEmpty() },
                priority = previous?.priority ?: session.priority,
            )
        }
    }

    private fun remapAdjustments(
        original: Schedule,
        incoming: Schedule,
        before: Courses,
        proposed: Courses,
        matched: Boolean,
        globalDays: List<DayOverride>,
        issues: MutableList<String>,
    ): List<SessionOverride> {
        val oldSessions = before.sessions.associateBy { it.id }
        val calendar = original.copy(maxWeeks = maxOf(original.maxWeeks, incoming.maxWeeks))
        val mapped = before.adjustments.mapNotNull { adjustment ->
            val old = oldSessions.getValue(adjustment.sessionId)
            val date = LocalDate.parse(adjustment.originalDate)
            val source = CourseEngine.plannedSourceDate(original, date, globalDays)
            val available = if (matched && source != null) proposed.sessions.filter {
                CourseEngine.isScheduledOn(it, source, CourseEngine.week(calendar, source), calendar.maxWeeks)
            } else emptyList()
            val sameTime = available.filter { it.time() == old.time() }
            val unchanged = proposed.sessions.firstOrNull {
                matched && it.id == old.id && it.placement() == old.placement() && it.weeks.containsAll(old.weeks)
            }
            val replacement = sameTime.find { it.id == old.id } ?: sameTime.singleOrNull() ?: unchanged
                ?: available.singleOrNull()?.takeIf { sameTime.isEmpty() }
            val label = when {
                adjustment.cancelled -> "${adjustment.originalDate} 的停课"
                adjustment.targetDate != null && adjustment.targetDate != adjustment.originalDate ->
                    "从 ${adjustment.originalDate} 移至 ${adjustment.targetDate} 的调课"
                else -> "${adjustment.originalDate} 的单次调课"
            }
            when {
                replacement == null -> issues += "${label}无法对应到导入时段，采用导入安排会移除这笔调整。"
                replacement.time() != old.time() -> issues += "${label}对应的上课时间已变更，采用导入安排仍会沿用这笔调整。"
            }
            replacement?.let { adjustment.copy(sessionId = it.id) }
        }
        return mapped.groupBy { it.sessionId to it.originalDate }.values.mapNotNull { matches ->
            if (matches.size > 1) issues += "${matches.first().originalDate} 有多笔调整对应同一导入时段，采用导入安排会移除这些调整。"
            matches.singleOrNull()
        }
    }

    private fun findOverlaps(schedule: Schedule, globalDays: List<DayOverride>): List<Overlap> {
        val monday = LocalDate.parse(schedule.week1Monday)
        val dates = sortedSetOf<LocalDate>()
        schedule.sessions.filterNot { it.mode == TimeMode.UNTIMED }.forEach { session ->
            if (session.date != null) dates += LocalDate.parse(session.date)
            else session.weeks.forEach { week -> dates += monday.plusWeeks(week.toLong() - 1).plusDays(session.day.toLong() - 1) }
        }
        (schedule.dayOverrides + globalDays).forEach { dates += LocalDate.parse(it.date) }
        schedule.sessionOverrides.mapNotNull { it.targetDate }.forEach { dates += LocalDate.parse(it) }
        return dates.flatMap { date ->
            val occurrences = CourseEngine.resolveDay(schedule, date, globalDays)
            occurrences.flatMapIndexed { index, first ->
                occurrences.subList(index + 1, occurrences.size).takeWhile { it.start < first.end }
                    .map { Overlap(first, it) }
            }
        }
    }

    private fun overlapKey(overlap: Overlap): Pair<LocalDate, List<String>> = overlap.first.date to
        listOf(overlap.first, overlap.second).map { "${it.course.id}|${it.start}|${it.end}" }.sorted()
}
