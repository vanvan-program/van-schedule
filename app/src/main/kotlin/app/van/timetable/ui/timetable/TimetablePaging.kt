// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import java.time.LocalDate

/**
 * 周课表与日列表共用的页面换算规则。
 *
 * 这里只放纯函数：不读 Compose 状态、不碰课表数据、不做协程调度，
 * 所以周/日双模式的换算与边界行为可以直接在 JVM 上测（见 `TimetablePagingTest`）。
 * 需要课表数据的部分（第几周、该周周一的日期）留在 [TimetablePagerState] 里。
 */
internal object TimetablePaging {

    /** 周次 → 周分页下标（第 1 周是第 0 页）。 */
    fun pageOfWeek(week: Int): Int = week - 1

    /** 周分页下标 → 周次。 */
    fun weekOfPage(page: Int): Int = page + 1

    /** 日期 → 日分页下标；窗口之外钳到首尾页，不抛异常。 */
    fun dayPage(anchorEpochDay: Long, date: LocalDate): Int =
        (DAY_PAGER_ANCHOR.toLong() + date.toEpochDay() - anchorEpochDay)
            .coerceIn(0L, DAY_PAGER_COUNT - 1L)
            .toInt()

    /** 日分页下标 → 日期。 */
    fun dayDate(anchorEpochDay: Long, page: Int): LocalDate =
        LocalDate.ofEpochDay(anchorEpochDay + page - DAY_PAGER_ANCHOR)

    /** 星期一为 0，与 `selectedWeekday` 一致。 */
    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    /** 某周第 [weekdayIndex] 天（0 = 周一）。 */
    fun dateAtWeekDay(week1Monday: LocalDate, week: Int, weekdayIndex: Int): LocalDate =
        week1Monday.plusWeeks((week - 1).toLong()).plusDays(weekdayIndex.toLong())

    fun clampWeek(week: Int, maxWeeks: Int): Int = week.coerceIn(1, maxWeeks)

    fun clampWeekPage(page: Int, maxWeeks: Int): Int = page.coerceIn(0, maxWeeks - 1)

    /**
     * 拖拽到边缘后是否该翻页：-1 向左、1 向右、0 不动。
     * 到首尾页时不再给出方向，避免拖到第 1 周还一直触发翻页延迟。
     */
    fun dragEdgeDirection(
        relativeX: Float,
        viewportWidthPx: Float,
        edgePx: Float,
        page: Int,
        maxWeeks: Int,
    ): Int =
        when {
            viewportWidthPx <= 0f -> 0
            relativeX < edgePx && page > 0 -> -1
            relativeX > viewportWidthPx - edgePx && page < maxWeeks - 1 -> 1
            else -> 0
        }
}