// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * 课间空档的高度规则。
 *
 * 0.5.10 起不再画午休 / 晚休横带（用户反馈「删除午休晚休的横条」），
 * 但空档高度仍按真实时长分配：10 分钟的课间只留一条细缝，两小时的午休才占整块。
 * 这个文件只验证高度，不再验证「哪段空档算午休」。
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
    fun `ten minute breaks stay slim`() {
        val timeline = timeline()
        val tenMinuteGaps =
            timeline.bands.filter {
                it.section == null && it.start != LocalTime.MIN &&
                    it.end != LocalTime.MAX &&
                    java.time.Duration.between(it.start, it.end).toMinutes() < 30
            }
        assertTrue("11 节课的作息里应该存在 10 分钟课间", tenMinuteGaps.isNotEmpty())
        tenMinuteGaps.forEach {
            assertTrue("10 分钟课间应当只剩细缝：${it.start}-${it.end}", it.units <= 0.06f)
        }
    }

    @Test
    fun `long meal gaps keep the full height`() {
        val timeline = timeline()
        fun unitsAt(start: LocalTime) = timeline.bands.first { it.start == start }.units
        val lunch = unitsAt(LocalTime.of(12, 10))
        val dinner = unitsAt(LocalTime.of(18, 10))
        assertTrue("两小时的午休空档应当明显高于 10 分钟课间", lunch >= 0.6f)
        assertTrue("一小时的晚休空档应当明显高于 10 分钟课间", dinner >= 0.4f)
        assertTrue("两小时的午休不应比一小时的晚休更矮", lunch > dinner)
    }

    @Test
    fun `forty minute morning break is taller than a ten minute one but shorter than lunch`() {
        val timeline = timeline()
        fun unitsAt(start: LocalTime) = timeline.bands.first { it.start == start }.units
        val morning = unitsAt(LocalTime.of(9, 50))
        val tenMinute = unitsAt(LocalTime.of(8, 55))
        val lunch = unitsAt(LocalTime.of(12, 10))
        assertTrue("40 分钟课间应当比 10 分钟课间更高", morning > tenMinute)
        assertTrue("40 分钟课间应当比两小时午休更矮", morning < lunch)
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
