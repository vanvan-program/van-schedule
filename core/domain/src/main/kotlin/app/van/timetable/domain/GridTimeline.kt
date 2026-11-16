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

    /**
     * 两个节次之间的空档属于哪一段休息。
     *
     * 只看「前面有课、后面也有课」的空档：作息表首尾那两个空档（0 点到第一节课、最后一节课到 24 点）
     * 只是时间轴的两端，标成「午休」会很怪。空档还必须真的够长——10 分钟的课间不是午休，
     * 否则每个课间都会横一条带子。
     */
    fun breakKindOf(band: Band): BreakKind? {
        if (band.section != null) return null
        val index = bands.indexOf(band)
        if (index <= 0 || index >= bands.lastIndex) return null
        if (bands[index - 1].section == null || bands[index + 1].section == null) return null
        if (Duration.between(band.start, band.end).toMinutes() < MIN_BREAK_MINUTES) return null
        return when {
            band.start >= LocalTime.of(10, 0) &&
                band.start <= LocalTime.of(13, 30) &&
                band.end <= LocalTime.of(15, 30) -> BreakKind.LUNCH
            band.start >= LocalTime.of(16, 0) && band.end <= LocalTime.of(20, 30) -> BreakKind.DINNER
            else -> null
        }
    }

    enum class BreakKind {
        LUNCH,
        DINNER,
    }

    companion object {
        /** 短于这个时长的课间不画休息横带：10 分钟课间画一条「午休」只会添乱。 */
        const val MIN_BREAK_MINUTES = 30L
        /** Check the whole timetable so paging never changes the spacing policy. */
        fun canCollapseGaps(schedule: Schedule): Boolean {
            val first = LocalDate.parse(schedule.week1Monday)
            val dates = (0 until schedule.maxWeeks * 7).map { first.plusDays(it.toLong()) }.toMutableSet()
            dates += schedule.sessions.mapNotNull { it.date?.let(LocalDate::parse) }
            dates += schedule.dayOverrides.map { LocalDate.parse(it.date) }
            dates += schedule.sessionOverrides.flatMap { listOfNotNull(it.originalDate, it.targetDate).map(LocalDate::parse) }
            // Include the courses shown in holiday previews as well as ordinary days.
            val visible = schedule.copy(holidays = emptyList())
            val timelines = mutableMapOf<List<Section>, GridTimeline>()
            return dates.all { date ->
                // The weekly grid uses Monday's routine for its shared axis.
                val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
                val sections = CourseEngine.routine(schedule, monday).sections
                val timeline = timelines.getOrPut(sections) { create(sections) }
                val times = CourseEngine.timetableDay(visible, date).map { it.start to it.end }.toMutableList()
                // Non-current section courses can also appear as grey previews.
                val daySections = CourseEngine.routine(schedule, date).sections.associateBy { it.number }
                times += schedule.sessions.filter {
                    it.date == null && it.mode == TimeMode.SECTIONS && it.day == date.dayOfWeek.value
                }.map { LocalTime.parse(daySections.getValue(it.startSection).start) to LocalTime.parse(daySections.getValue(it.endSection).end) }
                times.all { (start, end) ->
                    // A multi-section lesson may cross breaks, but neither boundary
                    // may fall inside a break, where collapsing would hide time.
                    timeline.bands.any { it.section != null && start >= it.start && start < it.end } &&
                        timeline.bands.any { it.section != null && end > it.start && end <= it.end }
                }
            }
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
