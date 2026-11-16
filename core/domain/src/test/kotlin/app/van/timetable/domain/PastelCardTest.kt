// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 柔和卡片配色与休息带判定。
 *
 * 这两条规则原先不存在：卡片是实色 + 对比字，休息段只是被压扁的空白。
 * 改成「浅色底 + 同色系文字 + 午休/晚休横带」之后，色相保持与分段归属都能在 JVM 上验证。
 */
class PastelCardTest {

    @Test
    fun `pastel text keeps the hue and reaches readable contrast`() {
        CoursePalette.light.forEach { fill ->
            listOf(false, true).forEach { dark ->
                // 与网格里一样：课程色 18% 覆盖在页面底色上
                val card = if (dark) blend(fill, 0xFF121212L, 0.18) else blend(fill, 0xFFF8F9FE, 0.18)
                val text = CoursePalette.pastelTextArgb(fill, card, dark)
                val ratio = contrast(text, card)
                assertTrue("fill=${fill.toString(16)} dark=$dark ratio=$ratio", ratio >= 4.5)
                assertEquals(
                    "hue drift for ${fill.toString(16)}",
                    hueOf(fill),
                    hueOf(text),
                    2.0,
                )
            }
        }
    }

    @Test
    fun `darker fills do not collapse into grey text`() {
        // 色相保留的可见证据：绿卡的文字仍是绿色，蓝卡仍是蓝色
        fun saturationOf(color: Long): Double {
            val max = maxOf(channel(color, 16), channel(color, 8), channel(color, 0))
            val min = minOf(channel(color, 16), channel(color, 8), channel(color, 0))
            return if (max == 0.0) 0.0 else (max - min) / max
        }
        CoursePalette.light.forEach { fill ->
            val text = CoursePalette.pastelTextArgb(fill, blend(fill, 0xFFFFFFFFL, 0.18), false)
            assertTrue("fill=${fill.toString(16)}", saturationOf(text) > 0.3)
        }
    }

    @Test
    fun `pastel text is darker than the fill in the light theme`() {
        CoursePalette.light.forEach { fill ->
            val card = blend(fill, 0xFFF8F9FE, 0.18)
            assertTrue(luminance(CoursePalette.pastelTextArgb(fill, card, false)) < luminance(fill))
        }
    }

    @Test
    fun `pastel text is lighter than the card in the dark theme`() {
        CoursePalette.light.forEach { fill ->
            val card = blend(fill, 0xFF121212L, 0.18)
            assertTrue(luminance(CoursePalette.pastelTextArgb(fill, card, true)) > luminance(card))
        }
    }

    @Test
    fun `gap between morning and afternoon is lunch`() {
        val timeline = GridTimeline.create(lunchSchedule)
        val gap = timeline.bands.first { it.section == null && it.start == LocalTime.of(11, 30) }
        assertEquals(GridTimeline.BreakKind.LUNCH, timeline.breakKindOf(gap))
    }

    @Test
    fun `gap after late afternoon is dinner`() {
        val timeline = GridTimeline.create(dinnerSchedule)
        val gap = timeline.bands.first { it.section == null && it.start == LocalTime.of(17, 25) }
        assertEquals(GridTimeline.BreakKind.DINNER, timeline.breakKindOf(gap))
    }

    @Test
    fun `leading and trailing gaps are never labelled`() {
        val timeline = GridTimeline.create(lunchSchedule)
        assertEquals(LocalTime.MIN, timeline.bands.first().start)
        assertNull(timeline.bands.first().section)
        assertNull(timeline.breakKindOf(timeline.bands.first()))
        assertNull(timeline.breakKindOf(timeline.bands.last()))
    }

    @Test
    fun `short gaps between adjacent sections are not breaks`() {
        val timeline = GridTimeline.create(lunchSchedule)
        // 第 1、2 节之间只隔 10 分钟
        val gap = timeline.bands.first { it.section == null && it.start == LocalTime.of(8, 45) }
        assertNull(timeline.breakKindOf(gap))
    }

    private val lunchSchedule =
        listOf(
            Section(1, "08:00", "08:45"),
            Section(2, "08:55", "09:40"),
            Section(3, "09:50", "10:35"),
            Section(4, "10:45", "11:30"),
            Section(5, "14:00", "14:45"),
            Section(6, "14:55", "15:40"),
        )

    private val dinnerSchedule =
        listOf(
            Section(1, "08:00", "08:45"),
            Section(2, "14:00", "14:45"),
            Section(3, "14:55", "17:25"),
            Section(4, "18:25", "19:10"),
        )

    private fun channel(color: Long, shift: Int): Double = ((color shr shift) and 255) / 255.0

    /** 色相角，用于验证「绿卡仍然是绿字」。 */
    private fun hueOf(color: Long): Double {
        val r = channel(color, 16)
        val g = channel(color, 8)
        val b = channel(color, 0)
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue =
            when {
                delta == 0.0 -> 0.0
                max == r -> 60.0 * (((g - b) / delta) % 6.0)
                max == g -> 60.0 * (((b - r) / delta) + 2.0)
                else -> 60.0 * (((r - g) / delta) + 4.0)
            }
        return if (hue < 0) hue + 360.0 else hue
    }

    private fun linearize(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Long): Double =
        0.2126 * linearize(channel(color, 16)) +
            0.7152 * linearize(channel(color, 8)) +
            0.0722 * linearize(channel(color, 0))

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** 与 Compose 的 alpha 合成同义：top 覆盖在 bottom 上。 */
    private fun blend(top: Long, bottom: Long, alpha: Double): Long {
        fun mix(shift: Int): Long {
            val t = ((top shr shift) and 255) * alpha
            val b = ((bottom shr shift) and 255) * (1 - alpha)
            return (t + b).toLong().coerceIn(0L, 255L)
        }
        return (0xFFL shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}