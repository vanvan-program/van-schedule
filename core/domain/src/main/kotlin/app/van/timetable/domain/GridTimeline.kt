// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.Duration
import java.time.LocalTime
import java.time.LocalDate

/** A routine defines the geometry. Courses never insert or enlarge a band. */
class GridTimeline private constructor(val bands: List<Band>) {
    data class Band(val start: LocalTime, val end: LocalTime, val units: Float, val section: Int?)

    val units = bands.sumOf { it.units.toDouble() }.toFloat()

    fun position(time: LocalTime): Float {
        var offset = 0f
        for (band in bands) {
            if (time <= band.start) return offset
            if (time < band.end) {
                val span = band.end.toNanoOfDay() - band.start.toNanoOfDay()
                return offset + band.units * ((time.toNanoOfDay() - band.start.toNanoOfDay()).toDouble() / span).toFloat()
            }
            offset += band.units
        }
        return offset
    }

    fun gapContaining(start: LocalTime, end: LocalTime): Band? =
        bands.firstOrNull { it.section == null && start >= it.start && end <= it.end }

    private fun fitsSections(start: LocalTime, end: LocalTime): Boolean =
        bands.any { it.section != null && start >= it.start && start < it.end } &&
            bands.any { it.section != null && end > it.start && end <= it.end }

    companion object {
        /** Check the whole timetable so paging never changes the spacing policy. */
        fun canCollapseGaps(schedule: Schedule): Boolean = canCollapseGaps(schedule, emptyList())

        fun canCollapseGaps(schedule: Schedule, globalDays: List<DayOverride>): Boolean {
            // With one effective routine, section-based lessons (including moved lessons
            // and inactive previews) start and end on the grid's own section boundaries.
            if (schedule.routineRules.isEmpty() &&
                schedule.sessions.none { it.mode == TimeMode.CLOCK } &&
                schedule.sessionOverrides.none { it.start != null || it.end != null }
            ) return true

            val first = LocalDate.parse(schedule.week1Monday)
            val axes = mutableMapOf<List<Section>, GridTimeline>()
            fun axis(sections: List<Section>) = axes.getOrPut(sections) { create(sections, 0f) }
            val possibleAxes = if (schedule.routineRules.isEmpty()) {
                listOf(axis(CourseEngine.routine(schedule, first).sections))
            } else schedule.routines.map { axis(it.sections) }.distinct()
            fun fitsEveryAxis(start: String, end: String): Boolean {
                val a = LocalTime.parse(start)
                val b = LocalTime.parse(end)
                return possibleAxes.all { it.fitsSections(a, b) }
            }

            val outside = schedule.sessions.filter { it.mode == TimeMode.CLOCK && !fitsEveryAxis(it.start, it.end) }
            val outsideIds = outside.mapTo(hashSetOf()) { it.id }
            val overrides = schedule.sessionOverrides.filter { rule ->
                !rule.cancelled && (rule.sessionId in outsideIds || when {
                    rule.start == null && rule.end == null -> false
                    rule.start == null || rule.end == null -> true
                    else -> !fitsEveryAxis(rule.start, rule.end)
                })
            }
            if (outside.isEmpty() && overrides.isEmpty() && schedule.routineRules.isEmpty()) return true

            // Resolve actual occurrences: a raw out-of-band lesson may be cancelled or moved.
            val candidates = sequence {
                for (session in outside) {
                    if (session.date != null) yield(LocalDate.parse(session.date))
                    else for (week in session.weeks) {
                        if (week in 1..schedule.maxWeeks)
                            yield(first.plusWeeks(week - 1L).plusDays(session.day - 1L))
                    }
                }
                for (rule in overrides) yield(LocalDate.parse(rule.targetDate ?: rule.originalDate))
                if (outside.isNotEmpty()) {
                    for (day in schedule.dayOverrides + globalDays) {
                        if (!day.cancelled && day.sourceDate != null) yield(LocalDate.parse(day.date))
                    }
                }
                if (schedule.routineRules.isNotEmpty()) yieldAll(routineTransitionDates(schedule, globalDays, first))
            }
            val visible = schedule.copy(holidays = emptyList())
            return candidates.distinct().all { day ->
                val monday = day.minusDays(day.dayOfWeek.value - 1L)
                val sections = CourseEngine.routine(schedule, monday).sections
                val timeline = axis(sections)
                if (CourseEngine.resolveDay(visible, day, globalDays).any { !timeline.fitsSections(it.start, it.end) })
                    return@all false
                val daySections = CourseEngine.routine(schedule, day).sections
                if (daySections == sections) return@all true
                // The grid may show inactive section lessons from other weeks in grey.
                val byNumber = daySections.associateBy { it.number }
                schedule.sessions.asSequence().filter {
                    it.mode == TimeMode.SECTIONS && it.date == null && it.day == day.dayOfWeek.value
                }.all {
                    timeline.fitsSections(LocalTime.parse(byNumber.getValue(it.startSection).start),
                        LocalTime.parse(byNumber.getValue(it.endSection).end))
                }
            }
        }

        /** Only a mid-week routine change can put a section lesson off Monday's shared axis. */
        private fun routineTransitionDates(
            schedule: Schedule, globalDays: List<DayOverride>, first: LocalDate,
        ): Sequence<LocalDate> = sequence {
            val end = first.plusWeeks(schedule.maxWeeks.toLong())
            val extra = buildSet {
                schedule.sessions.mapNotNullTo(this) { it.date }
                (schedule.dayOverrides + globalDays).mapTo(this) { it.date }
                schedule.sessionOverrides.forEach { rule ->
                    add(rule.originalDate)
                    rule.targetDate?.let(::add)
                }
            }
            for (rule in schedule.routineRules) {
                val through = LocalDate.parse(rule.through)
                val after = if (through == LocalDate.MAX) null else through.plusDays(1)
                for (change in listOfNotNull(LocalDate.parse(rule.from), after)) {
                    if (change.dayOfWeek.value == 1) continue
                    for (offset in 0..(7 - change.dayOfWeek.value)) {
                        val day = change.plusDays(offset.toLong())
                        if ((day >= first && day < end) || day.toString() in extra) yield(day)
                    }
                }
            }
        }

        /** Collapse breaks unless a visible lesson would otherwise have too little height. */
        fun compact(sections: List<Section>, lessons: List<Pair<LocalTime, LocalTime>>): GridTimeline {
            val axis = create(sections, 0f)
            val shortLessons = lessons.filter { (start, end) -> axis.position(end) - axis.position(start) < 0.5f }
            if (shortLessons.isEmpty()) return axis
            return create(sections, 0.65f, gapUnitsByMinutes = { 0f }, occupied = shortLessons, edgeGapUnits = 0f)
        }

        fun create(sections: List<Section>, gapUnits: Float = 20f / 56f): GridTimeline =
            create(sections, gapUnits, gapUnitsByMinutes = null, occupied = emptyList())

        /**
         * [gapUnitsByMinutes] 让空档高度按时长变化，而不是每个课间都占一样高：
         * 10 分钟的课间只留一条细缝，两小时的午休才占整块。
         * [edgeGapUnits] 是作息表首尾两段空档（0 点到第一节课、最后一节课到 24 点）的高度，
         * 它们只是时间轴的两端，默认跟 [gapUnits] 一样，展开空档时应当收成细缝。
         * [occupied] 是被课程占用的时间范围，这些空档必须保留 [gapUnits]，否则课会挤成一条线。
         */
        fun create(
            sections: List<Section>,
            gapUnits: Float,
            gapUnitsByMinutes: ((Long) -> Float)?,
            occupied: List<Pair<LocalTime, LocalTime>>,
            edgeGapUnits: Float = gapUnits,
        ): GridTimeline {
            require(sections.isNotEmpty()) { "作息不能为空" }
            require(gapUnits >= 0f && gapUnits.isFinite())
            val result = mutableListOf<Band>()
            var previous = LocalTime.MIN
            fun addGap(start: LocalTime, end: LocalTime, edge: Boolean) {
                val minutes = Duration.between(start, end).toMinutes()
                val preferred =
                    if (edge) edgeGapUnits else gapUnitsByMinutes?.invoke(minutes) ?: gapUnits
                val used = occupied.any { it.first < end && it.second > start }
                val units = if (used) maxOf(preferred, gapUnits) else preferred
                if (units > 0f && units.isFinite()) result += Band(start, end, units, null)
            }
            sections.sortedBy { it.number }.forEach { section ->
                val start = LocalTime.parse(section.start)
                val end = LocalTime.parse(section.end)
                if (previous < start) addGap(previous, start, edge = result.isEmpty())
                result += Band(start, end, 1f, section.number)
                previous = end
            }
            if (previous < LocalTime.MAX) addGap(previous, LocalTime.MAX, edge = true)
            return GridTimeline(result)
        }
    }
}
