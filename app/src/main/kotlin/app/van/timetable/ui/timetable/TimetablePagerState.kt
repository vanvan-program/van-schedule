// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.van.timetable.domain.CourseEngine
import app.van.timetable.domain.Schedule
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 课表页的周/日双模式状态持有者。
 *
 * 0.4.1 时这些逻辑内联在 `Timetable` 里：两个 Pager、`daily` 开关、选中星期、选中日期、
 * 互斥的翻页任务，以及「切模式该跳到哪一页」的换算。它们与拖拽状态机、冲突弹窗无关，
 * 却和整页布局混在同一个作用域，既读不完也测不了。
 *
 * 现在换算规则放在 [TimetablePaging]（纯函数、JVM 可测），这里只做三件事：
 * 持有分页与选中状态、把操作翻译成目标页、取消上一次翻页任务。
 * 状态仍用 `rememberSaveable` 保存，键与 0.4.0／0.4.1 一致，升级后停留位置不丢。
 */
@Stable
internal class TimetablePagerState(
    val weekPager: PagerState,
    val dayPager: PagerState,
    private val dailyState: androidx.compose.runtime.MutableState<Boolean>,
    private val weekdayState: androidx.compose.runtime.MutableIntState,
    private val selectedDateState: androidx.compose.runtime.MutableState<String>,
    private val dayAnchorEpochDay: Long,
    private val schedule: Schedule,
    private val scope: CoroutineScope,
) {
    private var navigationJob: Job? = null

    /** true = 日列表。 */
    var daily: Boolean
        get() = dailyState.value
        private set(value) {
            dailyState.value = value
        }

    /** 选中星期，0 = 周一。 */
    var selectedWeekday: Int
        get() = weekdayState.intValue
        private set(value) {
            weekdayState.intValue = value
        }

    val weekSelectedDate: String get() = selectedDateState.value

    val dayDate: LocalDate
        get() = TimetablePaging.dayDate(dayAnchorEpochDay, dayPager.currentPage)

    /** 当前显示的周次：日列表模式下由日期反推。 */
    val week: Int
        get() =
            if (daily)
                TimetablePaging.clampWeek(
                    CourseEngine.week(schedule, dayDate),
                    schedule.maxWeeks,
                )
            else TimetablePaging.weekOfPage(weekPager.currentPage)

    /** 当前周的周一。 */
    val monday: LocalDate
        get() = LocalDate.parse(schedule.week1Monday).plusWeeks((week - 1).toLong())

    /** 顶栏与日列表标题显示的日期。 */
    val selectedDate: String
        get() = if (daily) dayDate.toString() else weekSelectedDate

    fun dayPageOf(date: LocalDate): Int = TimetablePaging.dayPage(dayAnchorEpochDay, date)

    fun dateOfDayPage(page: Int): LocalDate = TimetablePaging.dayDate(dayAnchorEpochDay, page)

    fun selectWeekday(date: LocalDate) {
        selectedWeekday = TimetablePaging.weekdayIndex(date)
    }

    private fun animate(action: suspend () -> Unit) {
        navigationJob?.cancel()
        navigationJob = scope.launch { action() }
    }

    /**
     * 切到日列表并定位到 [date]。
     *
     * 必须先 `requestScrollToPage` 再切模式：`scrollToPage` 会等隐藏的 Pager 完成一次布局，
     * 那一步在切换过程中不会发生，页面会停在旧日期。
     */
    fun showDay(date: LocalDate) {
        navigationJob?.cancel()
        dayPager.requestScrollToPage(dayPageOf(date))
        daily = true
    }

    /** 日列表前后翻一天。 */
    fun moveDate(days: Long) {
        animate { dayPager.animateScrollToPage(dayPageOf(dayDate.plusDays(days))) }
    }

    /** 周次直跳/前后一周；日列表模式下保持同一个星期几。 */
    fun moveWeek(target: Int) {
        val targetWeek = TimetablePaging.clampWeek(target, schedule.maxWeeks)
        animate {
            if (daily) {
                val date =
                    TimetablePaging.dateAtWeekDay(
                        LocalDate.parse(schedule.week1Monday),
                        targetWeek,
                        TimetablePaging.weekdayIndex(dayDate),
                    )
                dayPager.animateScrollToPage(dayPageOf(date))
            } else {
                selectedDateState.value =
                    CourseEngine.dateAt(schedule, targetWeek, selectedWeekday + 1).toString()
                weekPager.animateScrollToPage(TimetablePaging.pageOfWeek(targetWeek))
            }
        }
    }

    /** 回到本周/今日，同时把选中星期对齐到 [today]。 */
    fun jumpToday(today: LocalDate) {
        animate {
            selectWeekday(today)
            if (daily) dayPager.animateScrollToPage(dayPageOf(today))
            else
                weekPager.animateScrollToPage(
                    TimetablePaging.pageOfWeek(
                        TimetablePaging.clampWeek(
                            CourseEngine.week(schedule, today),
                            schedule.maxWeeks,
                        )
                    )
                )
            selectedDateState.value = today.toString()
        }
    }

    /** 周课表 ↔ 日列表：把当前周的星期几 / 当前日的周次带过去。 */
    fun toggleMode() {
        if (daily) {
            navigationJob?.cancel()
            selectWeekday(dayDate)
            selectedDateState.value = dayDate.toString()
            weekPager.requestScrollToPage(
                TimetablePaging.pageOfWeek(
                    TimetablePaging.clampWeek(
                        CourseEngine.week(schedule, dayDate),
                        schedule.maxWeeks,
                    )
                )
            )
            daily = false
        } else showDay(LocalDate.parse(selectedDate))
    }

    /** 周分页翻到 [page] 后，把选中日期同步成该周的同一天。 */
    fun syncSelectedDateWithPage(page: Int) {
        if (daily) return
        val targetWeek = TimetablePaging.weekOfPage(page)
        val current =
            TimetablePaging.clampWeek(
                CourseEngine.week(schedule, LocalDate.parse(weekSelectedDate)),
                schedule.maxWeeks,
            )
        if (current != targetWeek) {
            selectedDateState.value =
                CourseEngine.dateAt(schedule, targetWeek, selectedWeekday + 1).toString()
        }
    }

    /**
     * 拖拽到左右边缘时自动翻一页。
     * [onPaging] 在真正开始翻页前调用（用于触感反馈），返回 true 表示翻了页。
     */
    suspend fun autoPageTowardEdge(
        relativeX: Float,
        viewportWidthPx: Float,
        edgePx: Float,
        onPaging: () -> Unit = {},
    ): Boolean {
        val page = weekPager.currentPage
        val direction =
            TimetablePaging.dragEdgeDirection(
                relativeX = relativeX,
                viewportWidthPx = viewportWidthPx,
                edgePx = edgePx,
                page = page,
                maxWeeks = schedule.maxWeeks,
            )
        if (direction == 0) return false
        delay(500L)
        val target = TimetablePaging.clampWeekPage(page + direction, schedule.maxWeeks)
        if (target == weekPager.currentPage) return false
        onPaging()
        weekPager.animateScrollToPage(target)
        return true
    }
}

/**
 * 建立（或复用）课表页的分页状态。
 * [initialWeek] 由调用方按「是否跳过空周末」算好，这里不再重复判断偏好。
 */
@Composable
internal fun rememberTimetablePagerState(
    s: Schedule,
    anchor: LocalDate,
    initialWeek: Int,
): TimetablePagerState {
    val weekPager =
        key(s.id, s.week1Monday, s.maxWeeks) {
            rememberPagerState(initialPage = initialWeek - 1) { s.maxWeeks }
        }
    val dayPager =
        key(s.id) {
            rememberPagerState(initialPage = DAY_PAGER_ANCHOR) { DAY_PAGER_COUNT }
        }
    val daily = rememberSaveable { mutableStateOf(false) }
    val weekday = rememberSaveable(s.id) { mutableIntStateOf(anchor.dayOfWeek.value - 1) }
    val selectedDate = rememberSaveable(s.id) { mutableStateOf(anchor.toString()) }
    val dayAnchor = rememberSaveable(s.id) { mutableLongStateOf(anchor.toEpochDay()) }
    val scope = rememberCoroutineScope()
    return remember(weekPager, dayPager, s, dayAnchor.longValue) {
        TimetablePagerState(
            weekPager = weekPager,
            dayPager = dayPager,
            dailyState = daily,
            weekdayState = weekday,
            selectedDateState = selectedDate,
            dayAnchorEpochDay = dayAnchor.longValue,
            schedule = s,
            scope = scope,
        )
    }
}