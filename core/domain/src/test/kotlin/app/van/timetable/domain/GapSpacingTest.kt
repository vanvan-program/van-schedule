// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * 课间空档的高度与休息横带规则。
 *
 * 用户反馈「横条还是很多、位置也不对」，根因是旧的判定只看了钟点区间：
 * 10:00 之后 15:00 之前的任何空档（连 10 分钟课间）都会画成「午休」。
 */
class GapSpacingTest {
    private val elevenSections =
        listOf(
            Section(1, "08:10", "08:55"),
            Section(2, "09:05", "09:50"),
            Section(3, "10:30", "11:15"),
            Section(4, "11:25", "12:10"),
            Section(5, "14:10", "14:55"),
            Section(6, "15:05", "15:50"),
            Section(7, "16:30", "17:15"),
            Section(8, "17:25", "18:10"),
            Section(9, "19:15", "20:00"),
            Section(10, "20:10", "20:55"),
            Section(11, "21:05", "21:50"),
        )

    private fun gapUnitsByMinutes(compact: Boolean = false): (Long) -> Float = { minutes ->
        if (compact) 0f
        else if (minutes >= 20L) (0.42f * minutes / 60f).coerceIn(0.2f, 0.9f)
        else 0.05f
    }

    private fun timeline(occupied: List<Pair<LocalTime, LocalTime>> = emptyList()) =
        GridTimeline.create(elevenSections, 0.42f, gapUnitsByMinutes(), occupied, edgeGapUnits = 0.05f)

    @Test
    fun `ten minute breaks are never lunch or dinner`() {
        val timeline = timeline()
        val tenMinuteGaps =
            timeline.bands.filter {
                it.section == null && it.start != LocalTime.MIN &&
                    it.end != LocalTime.MAX &&
                    java.time.Duration.between(it.start, it.end).toMinutes() < 30
            }
        assertTrue("11 节课的作息里应该存在 10 分钟课间", tenMinuteGaps.isNotEmpty())
        tenMinuteGaps.forEach { assertNull("10 分钟课间不应画休息横带：${it.start}-${it.end}", timeline.breakKindOf(it)) }
    }

    @Test
    fun `only the real lunch and dinner gaps are labelled`() {
        val timeline = timeline()
        val labelled = timeline.bands.mapNotNull { band -> timeline.breakKindOf(band)?.let { band.start to it } }
        assertEquals(listOf(LocalTime.of(12, 10) to GridTimeline.BreakKind.LUNCH, LocalTime.of(18, 10) to GridTimeline.BreakKind.DINNER), labelled)
    }

    @Test
    fun `forty minute morning break is not a meal`() {
        val timeline = timeline()
        val morning = timeline.bands.first { it.section == null && it.start == LocalTime.of(9, 50) }
        assertNull(timeline.breakKindOf(morning))
    }

    @Test
    fun `short breaks stay slim and long breaks keep their proportion`() {
        val timeline = timeline()
        fun unitsAt(start: LocalTime) = timeline.bands.first { it.start == start }.units
        assertTrue("10 分钟课间应当只剩细缝", unitsAt(LocalTime.of(8, 55)) <= 0.06f)
        val lunch = unitsAt(LocalTime.of(12, 10))
        val dinner = unitsAt(LocalTime.of(18, 10))
        assertTrue("午休应当明显高于 10 分钟课间", lunch >= 0.6f)
        assertTrue("晚休应当明显高于 10 分钟课间", dinner >= 0.4f)
        assertTrue("两小时的午休不应比一小时的晚休更矮", lunch > dinner)
    }

    @Test
    fun `edge gaps collapse when nothing is scheduled there`() {
        val timeline = timeline()
        val head = timeline.bands.first()
        val tail = timeline.bands.last()
        assertEquals(LocalTime.MIN, head.start)
        assertTrue("凌晨的空档不应撑开课表", head.units <= 0.06f)
        assertTrue("深夜的空档不应撑开课表", tail.units <= 0.06f)
    }

    @Test
    fun `a gap holding a clock course keeps its full height`() {
        val earlyCourse = LocalTime.of(7, 20) to LocalTime.of(8, 5)
        val timeline = timeline(listOf(earlyCourse))
        val head = timeline.bands.first()
        assertTrue("有课的凌晨空档必须保留高度，否则课程会被压成一条线", head.units >= 0.42f)
    }

    @Test
    fun `short gaps add up to far less than one row each`() {
        val timeline = timeline()
        val shortGapUnits =
            timeline.bands.filter { it.section == null && it.units <= 0.06f }.sumOf { it.units.toDouble() }.toFloat()
        // 旧实现每个空档固定占 24dp（约 0.43 行），短课间合计能吃掉好几行课程的高度。
        val allShortGaps =
            timeline.bands.count { it.section == null && it.units <= 0.06f }
        assertTrue("短课间应当存在", allShortGaps >= 5)
        assertTrue("短课间合计不应超过一行课程高度", shortGapUnits < 1f)
    }

    @Test
    fun `compact axis removes every gap`() {
        val timeline = GridTimeline.create(elevenSections, 0f, gapUnitsByMinutes(compact = true), emptyList(), edgeGapUnits = 0f)
        assertEquals(0f, timeline.units - timeline.bands.count { it.section != null }, 0.001f)
    }
}