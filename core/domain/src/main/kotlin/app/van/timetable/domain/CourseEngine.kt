// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.*
import java.time.temporal.ChronoUnit

object CourseEngine {
    /** Occurrence IDs retain the source date when a single lesson is moved. */
    fun originalDate(o: Occurrence): String = o.id.substringAfterLast('@', o.date.toString())

    fun cancelOccurrence(schedule: Schedule, occurrence: Occurrence): Schedule {
        val originalDate = originalDate(occurrence)
        val old = schedule.sessionOverrides.firstOrNull {
            it.sessionId == occurrence.session.id && it.originalDate == originalDate
        }
        val rule = (old ?: SessionOverride(occurrence.session.id, originalDate)).copy(cancelled = true)
        return schedule.copy(sessionOverrides = schedule.sessionOverrides.filterNot {
            it.sessionId == rule.sessionId && it.originalDate == rule.originalDate
        } + rule)
    }

    fun holiday(schedule: Schedule, date: LocalDate): Holiday? =
        schedule.holidays.firstOrNull {
            date >= LocalDate.parse(it.from) && date <= LocalDate.parse(it.through)
        }

    fun week(schedule: Schedule, date: LocalDate): Int =
        Math.floorDiv(
            ChronoUnit.DAYS.between(LocalDate.parse(schedule.week1Monday), date).toInt(),
            7,
        ) + 1

    fun routine(schedule: Schedule, date: LocalDate): TimeRoutine =
        schedule.routineRules
            .firstOrNull { date >= LocalDate.parse(it.from) && date <= LocalDate.parse(it.through) }
            ?.let { rule -> schedule.routines.first { it.id == rule.routineId } }
            ?: schedule.activeRoutineId?.let { id -> schedule.routines.firstOrNull { it.id == id } }
            ?: schedule.routines.first()

    fun dateAt(schedule: Schedule, week: Int, day: Int): LocalDate {
        require(week in 1..schedule.maxWeeks) { "周次必须在1–${schedule.maxWeeks}之间" }
        require(day in 1..7) { "星期必须在1–7之间" }
        return LocalDate.parse(schedule.week1Monday)
            .plusWeeks((week - 1).toLong())
            .plusDays((day - 1).toLong())
    }

    private fun raw(schedule: Schedule, source: LocalDate, display: LocalDate): List<Occurrence> {
        val w = week(schedule, source)
        val routine = routine(schedule, display)
        val courses = schedule.courses.associateBy { it.id }
        return schedule.sessions
            .filter { isScheduledOn(it, source, w, schedule.maxWeeks) }
            .map { s ->
                val start =
                    if (s.mode == TimeMode.CLOCK) LocalTime.parse(s.start)
                    else
                        LocalTime.parse(
                            routine.sections.first { it.number == s.startSection }.start
                        )
                val end =
                    if (s.mode == TimeMode.CLOCK) LocalTime.parse(s.end)
                    else LocalTime.parse(routine.sections.first { it.number == s.endSection }.end)
                Occurrence(
                    "${s.id}@$display",
                    courses.getValue(s.courseId),
                    s,
                    display,
                    start,
                    end,
                    s.place,
                    source != display || s.date != null,
                )
            }
    }

    internal fun isScheduledOn(session: CourseSession, source: LocalDate, week: Int, maxWeeks: Int): Boolean =
        session.mode != TimeMode.UNTIMED &&
            if (session.date != null) LocalDate.parse(session.date) == source
            else week in 1..maxWeeks && session.day == source.dayOfWeek.value && week in session.weeks

    private fun planned(
        schedule: Schedule,
        date: LocalDate,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> = plannedSourceDate(schedule, date, globalDayOverrides)
        ?.let { raw(schedule, it, date) }.orEmpty()

    internal fun plannedSourceDate(
        schedule: Schedule,
        date: LocalDate,
        globalDayOverrides: List<DayOverride>,
    ): LocalDate? {
        val rule =
            schedule.dayOverrides.firstOrNull { it.date == date.toString() }
                ?: globalDayOverrides.firstOrNull { it.date == date.toString() }
        if (rule?.cancelled == true) return null
        return rule?.sourceDate?.let(LocalDate::parse) ?: date
    }

    fun resolveDay(
        schedule: Schedule,
        date: LocalDate,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> {
        if (holiday(schedule, date) != null) return emptyList()
        val local =
            schedule.sessionOverrides
                .filter { it.originalDate == date.toString() }
                .associateBy { it.sessionId }
        val result =
            planned(schedule, date, globalDayOverrides)
                .mapNotNull { o ->
                    val override = local[o.session.id] ?: return@mapNotNull o
                    if (
                        override.cancelled ||
                            (override.targetDate != null && override.targetDate != date.toString())
                    )
                        null
                    else apply(o, override, date)
                }
                .toMutableList()
        schedule.sessionOverrides
            .filter {
                !it.cancelled &&
                    it.targetDate == date.toString() &&
                    it.originalDate != date.toString()
            }
            .forEach { override ->
                planned(schedule, LocalDate.parse(override.originalDate), globalDayOverrides)
                    .firstOrNull { it.session.id == override.sessionId }
                    ?.let { original ->
                        val newRoutine = routine(schedule, date)
                        val moved =
                            if (original.session.mode == TimeMode.SECTIONS)
                                original.copy(
                                    start =
                                        LocalTime.parse(
                                            newRoutine.sections
                                                .first {
                                                    it.number == original.session.startSection
                                                }
                                                .start
                                        ),
                                    end =
                                        LocalTime.parse(
                                            newRoutine.sections
                                                .first { it.number == original.session.endSection }
                                                .end
                                        ),
                                )
                            else original
                        result += apply(moved, override, date)
                    }
            }
        return result.sortedWith(
            compareBy<Occurrence> { it.start }
                .thenByDescending { it.session.priority }
                .thenBy { it.id }
        )
    }

    // Display-only preview. Widgets, reminders and next-course calculations use resolveDay.
    fun timetableDay(
        schedule: Schedule,
        date: LocalDate,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> =
        resolveDay(
            if (holiday(schedule, date) != null) schedule.copy(holidays = emptyList())
            else schedule,
            date,
            globalDayOverrides,
        )

    fun effectiveDisplayWeek(
        schedule: Schedule,
        date: LocalDate,
        autoSkipEmptyWeekend: Boolean,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): Int {
        val current = week(schedule, date).coerceIn(1, schedule.maxWeeks)
        if (!autoSkipEmptyWeekend) return current
        if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
            return current
        }
        val sat = dateAt(schedule, current, 6)
        val sun = dateAt(schedule, current, 7)
        val weekendEmpty =
            resolveDay(schedule, sat, globalDayOverrides).isEmpty() &&
                resolveDay(schedule, sun, globalDayOverrides).isEmpty()
        return if (weekendEmpty && current < schedule.maxWeeks) current + 1 else current
    }

    fun startNewSemester(
        schedule: Schedule,
        week1Monday: String,
        maxWeeks: Int = schedule.maxWeeks,
        newName: String = schedule.name,
    ): Schedule =
        schedule.copy(
            name = newName.trim().ifBlank { schedule.name },
            week1Monday = week1Monday,
            maxWeeks = maxWeeks,
            courses = emptyList(),
            sessions = emptyList(),
            dayOverrides = emptyList(),
            sessionOverrides = emptyList(),
            holidays = emptyList(),
        )

    fun slotConflicts(
        schedule: Schedule,
        week: Int,
        day: Int,
        startSection: Int,
        endSection: Int,
        excludeSessionId: String? = null,
    ): List<CourseSession> {
        val date = dateAt(schedule, week, day)
        val routine = routine(schedule, date)
        val targetStart =
            routine.sections.firstOrNull { it.number == startSection }?.start?.let(LocalTime::parse)
        val targetEnd =
            routine.sections.firstOrNull { it.number == endSection }?.end?.let(LocalTime::parse)
        return schedule.sessions.filter { s ->
            s.id != excludeSessionId &&
                s.date == null &&
                s.day == day &&
                week in s.weeks &&
                when (s.mode) {
                    TimeMode.SECTIONS -> s.startSection <= endSection && s.endSection >= startSection
                    TimeMode.CLOCK ->
                        targetStart != null &&
                            targetEnd != null &&
                            LocalTime.parse(s.start) < targetEnd &&
                            LocalTime.parse(s.end) > targetStart
                    TimeMode.UNTIMED -> false
                }
        }
    }

    private fun isMergeableSession(a: CourseSession, b: CourseSession): Boolean =
        a.courseId == b.courseId &&
            a.mode == TimeMode.SECTIONS &&
            b.mode == TimeMode.SECTIONS &&
            a.date == null &&
            b.date == null &&
            a.teacher == b.teacher &&
            a.place == b.place

    fun moveSessionForWeek(
        schedule: Schedule,
        sessionId: String,
        fromWeek: Int,
        targetWeek: Int,
        targetDay: Int,
        targetStartSection: Int,
        targetEndSection: Int,
    ): Schedule {
        val source = schedule.sessions.firstOrNull { it.id == sessionId } ?: return schedule
        if (source.mode != TimeMode.SECTIONS || source.date != null) return schedule
        if (fromWeek !in source.weeks) return schedule
        val sameSlot =
            source.day == targetDay &&
                source.startSection == targetStartSection &&
                source.endSection == targetEndSection
        if (sameSlot && fromWeek == targetWeek) return schedule

        val updatedSessions = schedule.sessions.toMutableList()
        val sourceIdx = updatedSessions.indexOfFirst { it.id == sessionId }
        val existingMerge =
            updatedSessions.indexOfFirst {
                it.id != sessionId &&
                    it.day == targetDay &&
                    it.startSection == targetStartSection &&
                    it.endSection == targetEndSection &&
                    isMergeableSession(it, source)
            }

        if (sameSlot) {
            if (targetWeek in source.weeks) return schedule
            updatedSessions[sourceIdx] = source.copy(weeks = (source.weeks - fromWeek) + targetWeek)
        } else if (existingMerge >= 0) {
            val target = updatedSessions[existingMerge]
            updatedSessions[existingMerge] = target.copy(weeks = target.weeks + targetWeek)
            val remainingWeeks = source.weeks - fromWeek
            if (remainingWeeks.isEmpty()) updatedSessions.removeAt(sourceIdx)
            else updatedSessions[sourceIdx] = source.copy(weeks = remainingWeeks)
        } else if (source.weeks.size == 1) {
            updatedSessions[sourceIdx] =
                source.copy(
                    day = targetDay,
                    startSection = targetStartSection,
                    endSection = targetEndSection,
                    weeks = setOf(targetWeek),
                )
        } else {
            updatedSessions[sourceIdx] = source.copy(weeks = source.weeks - fromWeek)
            updatedSessions +=
                source.copy(
                    id = newId(),
                    day = targetDay,
                    startSection = targetStartSection,
                    endSection = targetEndSection,
                    weeks = setOf(targetWeek),
                )
        }
        val fromDate = dateAt(schedule, fromWeek, source.day).toString()
        val cleanedOverrides =
            schedule.sessionOverrides.filterNot {
                it.sessionId == sessionId && it.originalDate == fromDate
            }
        return schedule.copy(sessions = updatedSessions, sessionOverrides = cleanedOverrides)
    }

    fun overwriteSessionForWeek(
        schedule: Schedule,
        sessionId: String,
        fromWeek: Int,
        targetWeek: Int,
        targetDay: Int,
        targetStartSection: Int,
        targetEndSection: Int,
    ): Schedule {
        val cleaned =
            schedule.sessions.mapNotNull { s ->
                if (
                    s.id != sessionId &&
                        s.date == null &&
                        s.mode == TimeMode.SECTIONS &&
                        s.day == targetDay &&
                        targetWeek in s.weeks &&
                        s.startSection <= targetEndSection &&
                        s.endSection >= targetStartSection
                ) {
                    val left = s.weeks - targetWeek
                    if (left.isEmpty()) null else s.copy(weeks = left)
                } else s
            }
        val validIds = cleaned.map { it.id }.toSet()
        val intermediate =
            schedule.copy(
                sessions = cleaned,
                sessionOverrides = schedule.sessionOverrides.filter { it.sessionId in validIds },
            )
        return moveSessionForWeek(
            intermediate,
            sessionId,
            fromWeek,
            targetWeek,
            targetDay,
            targetStartSection,
            targetEndSection,
        )
    }

    fun swapSessionsForWeek(
        schedule: Schedule,
        sourceSessionId: String,
        sourceWeek: Int,
        targetSessionId: String,
        targetWeek: Int,
    ): Schedule {
        val src = schedule.sessions.firstOrNull { it.id == sourceSessionId } ?: return schedule
        val dst = schedule.sessions.firstOrNull { it.id == targetSessionId } ?: return schedule
        if (src.mode != TimeMode.SECTIONS || dst.mode != TimeMode.SECTIONS) return schedule
        if (src.date != null || dst.date != null) return schedule
        if (sourceSessionId == targetSessionId && sourceWeek == targetWeek) return schedule

        val sessions = schedule.sessions.toMutableList()
        fun removeWeek(id: String, week: Int): CourseSession? {
            val idx = sessions.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val item = sessions[idx]
            val rest = item.weeks - week
            if (rest.isEmpty()) sessions.removeAt(idx) else sessions[idx] = item.copy(weeks = rest)
            return item
        }

        val srcRemoved = removeWeek(sourceSessionId, sourceWeek) ?: return schedule
        val dstRemoved = removeWeek(targetSessionId, targetWeek) ?: return schedule

        fun placeWeek(
            base: CourseSession,
            week: Int,
            day: Int,
            startSection: Int,
            endSection: Int,
        ) {
            val mergeIdx =
                sessions.indexOfFirst {
                    it.day == day &&
                        it.startSection == startSection &&
                        it.endSection == endSection &&
                        isMergeableSession(it, base)
                }
            if (mergeIdx >= 0) {
                sessions[mergeIdx] = sessions[mergeIdx].copy(weeks = sessions[mergeIdx].weeks + week)
            } else {
                sessions +=
                    base.copy(
                        id = if (sessions.none { it.id == base.id }) base.id else newId(),
                        day = day,
                        startSection = startSection,
                        endSection = endSection,
                        weeks = setOf(week),
                    )
            }
        }

        placeWeek(srcRemoved, targetWeek, dstRemoved.day, dstRemoved.startSection, dstRemoved.endSection)
        placeWeek(dstRemoved, sourceWeek, srcRemoved.day, srcRemoved.startSection, srcRemoved.endSection)

        val srcDate = dateAt(schedule, sourceWeek, srcRemoved.day).toString()
        val dstDate = dateAt(schedule, targetWeek, dstRemoved.day).toString()
        val validIds = sessions.map { it.id }.toSet()
        return schedule.copy(
            sessions = sessions,
            sessionOverrides =
                schedule.sessionOverrides.filter {
                    it.sessionId in validIds &&
                        !(it.sessionId == sourceSessionId && it.originalDate == srcDate) &&
                        !(it.sessionId == targetSessionId && it.originalDate == dstDate)
                },
        )
    }

    private fun apply(o: Occurrence, rule: SessionOverride, date: LocalDate) =
        o.copy(
            id = "${o.session.id}@${rule.originalDate}",
            date = date,
            start = rule.start?.let(LocalTime::parse) ?: o.start,
            end = rule.end?.let(LocalTime::parse) ?: o.end,
            place = rule.place ?: o.place,
            adjusted = true,
        )

    fun remaining(
        schedule: Schedule,
        now: Instant,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> =
        resolveDay(
                schedule,
                now.atZone(ZoneId.of(schedule.zone)).toLocalDate(),
                globalDayOverrides,
            )
            .filter { it.endsAt(ZoneId.of(schedule.zone)) > now }

    fun next(
        schedule: Schedule,
        now: Instant,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): Occurrence? {
        val date = now.atZone(ZoneId.of(schedule.zone)).toLocalDate()
        val last =
            maxOf(
                LocalDate.parse(schedule.week1Monday).plusWeeks(schedule.maxWeeks.toLong()),
                schedule.sessions.mapNotNull { it.date?.let(LocalDate::parse) }.maxOrNull() ?: date,
                schedule.sessionOverrides
                    .mapNotNull { it.targetDate?.let(LocalDate::parse) }
                    .maxOrNull() ?: date,
            )
        val laterMakeupDates = (schedule.dayOverrides + globalDayOverrides)
            .asSequence().filter { !it.cancelled && it.sourceDate != null }
            .map { LocalDate.parse(it.date) }
            .filter { it > last && it >= date }
            .distinct().sorted()
        val regularDates = generateSequence(date) { it.plusDays(1) }.takeWhile { it <= last }
        val zone = ZoneId.of(schedule.zone)
        for (d in regularDates + laterMakeupDates) {
            val available =
                resolveDay(schedule, d, globalDayOverrides).filter { it.endsAt(zone) > now }
            val ongoing = available.filter { it.startsAt(zone) <= now }
            val pool = ongoing.ifEmpty { available }
            pool.firstOrNull()?.let { first ->
                return conflicts(pool)
                    .firstOrNull { group -> group.any { it.id == first.id } }
                    ?.let(::main) ?: first
            }
        }
        return null
    }

    fun conflicts(items: List<Occurrence>): List<List<Occurrence>> {
        val groups = mutableListOf<MutableList<Occurrence>>()
        for (o in items.sortedBy { it.start }) {
            val group = groups.lastOrNull()
            if (group != null && group.any { overlaps(it, o) }) group += o
            else groups += mutableListOf(o)
        }
        return groups.filter { it.size > 1 }
    }

    fun overlaps(a: Occurrence, b: Occurrence): Boolean =
        a.date == b.date && a.start < b.end && b.start < a.end

    fun main(items: List<Occurrence>): Occurrence =
        items
            .sortedWith(compareByDescending<Occurrence> { it.session.priority }.thenBy { it.id })
            .first()

    fun special(schedule: Schedule, week: Int): List<String> =
        schedule.sessions
            .filter { it.mode == TimeMode.UNTIMED && week in it.weeks }
            .map { s -> schedule.courses.first { it.id == s.courseId }.name + " · " + s.place }
            .distinct()
}

object Validation {
    fun errors(s: Schedule): List<String> = buildList {
        fun check(ok: Boolean, message: String) {
            if (!ok) add(message)
        }
        val start = runCatching { LocalDate.parse(s.week1Monday) }.getOrNull()
        check(start != null && start.dayOfWeek == DayOfWeek.MONDAY, "第1周起点必须是周一")
        check(s.name.isNotBlank(), "课表名称不能为空")
        check(s.maxWeeks > 0, "总周数必须大于0")
        check(runCatching { ZoneId.of(s.zone) }.isSuccess, "课表时区无效")
        check(s.routines.isNotEmpty(), "至少需要一套作息")
        check(s.courses.map { it.id }.distinct().size == s.courses.size, "课程ID重复")
        check(s.sessions.map { it.id }.distinct().size == s.sessions.size, "时段ID重复")
        s.courses.forEach {
            check(it.name.isNotBlank(), "课程名称不能为空")
            check(it.color in CoursePalette.light.indices, "课程配色无效")
            check(
                it.customColor == null || it.customColor in 0xFF000000..0xFFFFFFFF,
                "自定义颜色必须为不透明颜色",
            )
            check(it.leadMinutes == null || it.leadMinutes in 0..1440, "提醒提前量无效")
            check(it.weekNotes.keys.all { w -> w in 1..s.maxWeeks }, "按周备注周次超出学期")
        }
        check(
            s.activeRoutineId == null || s.routines.any { it.id == s.activeRoutineId },
            "默认作息关联不存在",
        )
        check(s.routines.map { it.id }.distinct().size == s.routines.size, "作息ID重复")
        s.routines.forEach { r ->
            check(r.sections.isNotEmpty(), "${r.name}作息为空")
            check(
                r.sections.map { it.number }.sorted() == (1..r.sections.size).toList(),
                "${r.name}节次必须连续且唯一",
            )
            var previous: LocalTime? = null
            r.sections
                .sortedBy { it.number }
                .forEach { n ->
                    val a = runCatching { LocalTime.parse(n.start) }.getOrNull()
                    val b = runCatching { LocalTime.parse(n.end) }.getOrNull()
                    check(a != null && b != null && a < b, "第${n.number}节起止时间无效")
                    if (a != null && previous != null) check(a >= previous, "作息时间发生重叠")
                    previous = b
                }
        }
        s.sessions.forEach { n ->
            check(s.courses.any { it.id == n.courseId }, "时段关联的课程不存在")
            check(n.day in 1..7 || (n.mode == TimeMode.UNTIMED && n.day == 0), "星期超出范围")
            check(
                n.date != null || (n.weeks.isNotEmpty() && n.weeks.all { it in 1..s.maxWeeks }),
                "上课周次为空或超出学期",
            )
            check(n.date == null || runCatching { LocalDate.parse(n.date) }.isSuccess, "单次课程日期无效")
            when (n.mode) {
                TimeMode.SECTIONS ->
                    check(
                        n.startSection > 0 &&
                            n.endSection >= n.startSection &&
                            s.routines.all { r -> n.endSection <= r.sections.size },
                        "课程节次超出作息范围",
                    )
                TimeMode.CLOCK ->
                    check(
                        runCatching { LocalTime.parse(n.start) < LocalTime.parse(n.end) }
                            .getOrDefault(false),
                        "课程起止时间无效",
                    )
                TimeMode.UNTIMED -> Unit
            }
        }
        s.routineRules.forEach { r ->
            check(s.routines.any { it.id == r.routineId }, "作息规则关联不存在")
            check(
                runCatching { LocalDate.parse(r.from) <= LocalDate.parse(r.through) }
                    .getOrDefault(false),
                "作息生效日期无效",
            )
        }
        s.routineRules.forEachIndexed { i, a ->
            s.routineRules.drop(i + 1).forEach { b ->
                check(!(a.from <= b.through && b.from <= a.through), "作息规则日期重叠")
            }
        }
        check(s.dayOverrides.map { it.date }.distinct().size == s.dayOverrides.size, "同一天有多个整日安排")
        check(s.holidays.map { it.id }.distinct().size == s.holidays.size, "假期ID重复")
        s.holidays.forEach { holiday ->
            check(holiday.name.isNotBlank(), "假期名称不能为空")
            check(
                runCatching {
                        LocalDate.parse(holiday.from) <= LocalDate.parse(holiday.through)
                    }
                    .getOrDefault(false),
                "假期结束日期不能早于开始日期，日期需有效",
            )
        }
        s.dayOverrides.forEach { d ->
            check(
                runCatching {
                        LocalDate.parse(d.date)
                        d.sourceDate?.let { LocalDate.parse(it) }
                    }
                    .isSuccess,
                "整日调整日期无效",
            )
        }
        check(
            s.sessionOverrides.map { it.sessionId to it.originalDate }.distinct().size ==
                s.sessionOverrides.size,
            "同一课程重复调整",
        )
        s.sessionOverrides.forEach { o ->
            check(s.sessions.any { it.id == o.sessionId }, "调课关联的时段不存在")
            check(
                runCatching {
                        LocalDate.parse(o.originalDate)
                        o.targetDate?.let { LocalDate.parse(it) }
                    }
                    .isSuccess,
                "单次调整日期无效",
            )
            check((o.start == null) == (o.end == null), "调整时间需同时填写起止")
            if (o.start != null)
                check(
                    runCatching { LocalTime.parse(o.start) < LocalTime.parse(o.end) }
                        .getOrDefault(false),
                    "调整起止时间无效",
                )
        }
    }

    fun requireValid(s: Schedule) {
        val errors = errors(s)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
