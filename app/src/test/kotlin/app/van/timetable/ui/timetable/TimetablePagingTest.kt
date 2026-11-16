// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周/日双模式的页面换算。
 *
 * 这些规则原先内联在 `Timetable` 里，只能靠真机点出来；抽成纯函数后可以直接在 JVM 上验证边界：
 * 日分页窗口的首尾钳制、周次与页面的互换、以及拖拽到边缘该不该翻页。
 */
class TimetablePagingTest {

    /** 2026-03-02 是周一，作为多周计算的基准。 */
    private val week1Monday = LocalDate.of(2026, 3, 2)
    private val anchor = week1Monday.plusWeeks(3).plusDays(2) // 第 4 周的周三

    @Test
    fun `week and page are inverses`() {
        (1..20).forEach { week ->
            assertEquals(week, TimetablePaging.weekOfPage(TimetablePaging.pageOfWeek(week)))
        }
        assertEquals(0, TimetablePaging.pageOfWeek(1))
    }

    @Test
    fun `anchor date sits on the anchor page`() {
        assertEquals(
            DAY_PAGER_ANCHOR,
            TimetablePaging.dayPage(anchor.toEpochDay(), anchor),
        )
        assertEquals(anchor, TimetablePaging.dayDate(anchor.toEpochDay(), DAY_PAGER_ANCHOR))
    }

    @Test
    fun `day page and date round trip across the window`() {
        (-30..30).forEach { offset ->
            val date = anchor.plusDays(offset.toLong())
            val page = TimetablePaging.dayPage(anchor.toEpochDay(), date)
            assertEquals(date, TimetablePaging.dayDate(anchor.toEpochDay(), page))
        }
    }

    @Test
    fun `dates outside the window clamp to the first and last page`() {
        val tooEarly = anchor.minusYears(200)
        val tooLate = anchor.plusYears(200)
        assertEquals(0, TimetablePaging.dayPage(anchor.toEpochDay(), tooEarly))
        assertEquals(
            DAY_PAGER_COUNT - 1,
            TimetablePaging.dayPage(anchor.toEpochDay(), tooLate),
        )
    }

    @Test
    fun `weekday index starts at zero on monday`() {
        assertEquals(0, TimetablePaging.weekdayIndex(week1Monday))
        assertEquals(6, TimetablePaging.weekdayIndex(week1Monday.plusDays(6)))
    }

    @Test
    fun `date in week keeps the weekday and crosses month boundaries`() {
        assertEquals(week1Monday, TimetablePaging.dateAtWeekDay(week1Monday, 1, 0))
        assertEquals(LocalDate.of(2026, 3, 8), TimetablePaging.dateAtWeekDay(week1Monday, 1, 6))
        // 第 2 周周一 = 03-09，周日 = 03-15；第 6 周跨到 4 月。
        assertEquals(LocalDate.of(2026, 3, 15), TimetablePaging.dateAtWeekDay(week1Monday, 2, 6))
        assertEquals(LocalDate.of(2026, 4, 12), TimetablePaging.dateAtWeekDay(week1Monday, 6, 6))
    }

    @Test
    fun `week clamping keeps a single week schedule usable`() {
        assertEquals(1, TimetablePaging.clampWeek(0, 20))
        assertEquals(20, TimetablePaging.clampWeek(99, 20))
        assertEquals(7, TimetablePaging.clampWeek(7, 20))
        assertEquals(1, TimetablePaging.clampWeek(5, 1))
        assertEquals(0, TimetablePaging.clampWeekPage(5, 1))
        assertEquals(0, TimetablePaging.clampWeekPage(-3, 8))
    }

    @Test
    fun `drag at the left edge pages back only when a previous week exists`() {
        assertEquals(-1, edge(relativeX = 4f, page = 3, maxWeeks = 20))
        assertEquals(0, edge(relativeX = 4f, page = 0, maxWeeks = 20))
    }

    @Test
    fun `drag at the right edge pages forward only when a next week exists`() {
        assertEquals(1, edge(relativeX = 996f, page = 3, maxWeeks = 20))
        assertEquals(0, edge(relativeX = 996f, page = 19, maxWeeks = 20))
    }

    @Test
    fun `dragging in the middle never pages`() {
        assertEquals(0, edge(relativeX = 500f, page = 3, maxWeeks = 20))
        // 刚好压在边缘内侧仍算边缘，超出边缘一点点也一样。
        assertEquals(1, edge(relativeX = 969f, page = 3, maxWeeks = 20))
        assertEquals(0, edge(relativeX = 500f, page = 0, maxWeeks = 1))
    }

    @Test
    fun `zero viewport width disables edge paging`() {
        assertEquals(0, edge(relativeX = 0f, page = 3, maxWeeks = 20, viewportWidthPx = 0f))
    }

    @Test
    fun `day pager anchor is far enough from both ends for a century of scrolling`() {
        assertTrue(DAY_PAGER_COUNT > 70_000)
        assertTrue(DAY_PAGER_ANCHOR > 0)
        // 前后各一百年都不该被钳制在不同页上。
        val pageA = TimetablePaging.dayPage(anchor.toEpochDay(), anchor.minusYears(99))
        val pageB = TimetablePaging.dayPage(anchor.toEpochDay(), anchor.plusYears(99))
        assertTrue(pageA > 0)
        assertTrue(pageB < DAY_PAGER_COUNT - 1)
    }

    private fun edge(
        relativeX: Float,
        page: Int,
        maxWeeks: Int,
        viewportWidthPx: Float = 1000f,
        edgePx: Float = 32f,
    ) = TimetablePaging.dragEdgeDirection(
        relativeX = relativeX,
        viewportWidthPx = viewportWidthPx,
        edgePx = edgePx,
        page = page,
        maxWeeks = maxWeeks,
    )
}