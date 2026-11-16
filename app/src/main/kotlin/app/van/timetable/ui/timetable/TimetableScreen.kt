// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.van.timetable.domain.CourseEngine
import app.van.timetable.domain.GridTimeline
import app.van.timetable.domain.Occurrence
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.Schedule
import app.van.timetable.ui.Muted
import app.van.timetable.ui.WeekScrubber
import app.van.timetable.ui.weekdays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 课表页（周视图 / 日列表）。
 *
 * 0.4.0 时它是一个 670 行的 composable：分页、模式切换、拖拽状态机、冲突弹窗、顶栏菜单、
 * 课表下拉与整页布局全部内联在同一次作用域里。现在按职责拆成：
 * - 本文件：锚点与分页状态、拖拽状态机、整页布局；
 * - [TimetableHeader]：顶栏与它的两个下拉面板；
 * - [TimetableChrome]：下一节提示、周备注条、回到本周、拖拽提示与卡片副本；
 * - [TimetableDialogs]：周次直跳与冲突处理；
 * - [WeekGrid] / [TimetablePager] / [DayList]：三种视图。
 *
 * 状态仍与 0.4.0 完全一致（同样的 rememberSaveable 键与锚点算法），
 * 这样升级后用户停留的周次、日列表日期与选中星期都不会丢。
 */
@Composable
fun Timetable(
    s: Schedule,
    p: Preferences,
    now: Instant,
    nav: (String) -> Unit,
    initialDate: String? = null,
    schedules: List<Schedule> = listOf(s),
    onSelectSchedule: (String) -> Unit = {},
    onUpdateSchedule: (Schedule) -> Unit = {},
) {
    val dateNow = now.atZone(ZoneId.of(s.zone)).toLocalDate()
    val actualWeek = CourseEngine.week(s, dateNow)
    val anchor = runCatching { initialDate?.let(LocalDate::parse) }.getOrNull() ?: dateNow
    val initialWeek =
        remember(s.id, anchor, p.autoSkipEmptyWeekend, p.globalDayOverrides) {
            CourseEngine.effectiveDisplayWeek(s, anchor, p.autoSkipEmptyWeekend, p.globalDayOverrides)
        }
    var daily by rememberSaveable { mutableStateOf(false) }
    var selectedWeekday by rememberSaveable(s.id) { mutableIntStateOf(anchor.dayOfWeek.value - 1) }
    var weekSelectedDate by rememberSaveable(s.id) { mutableStateOf(anchor.toString()) }
    val dayAnchor by rememberSaveable(s.id) { mutableLongStateOf(anchor.toEpochDay()) }
    val weekPager =
        key(s.id, s.week1Monday, s.maxWeeks) {
            rememberPagerState(initialPage = initialWeek - 1) { s.maxWeeks }
        }
    val dayPager =
        key(s.id) {
            rememberPagerState(initialPage = DAY_PAGER_ANCHOR) { DAY_PAGER_COUNT }
        }
    val dayDate = LocalDate.ofEpochDay(dayAnchor + dayPager.currentPage - DAY_PAGER_ANCHOR)
    val week =
        if (daily) CourseEngine.week(s, dayDate).coerceIn(1, s.maxWeeks)
        else weekPager.currentPage + 1
    val monday = LocalDate.parse(s.week1Monday).plusWeeks((week - 1).toLong())
    val selectedDate = if (daily) dayDate.toString() else weekSelectedDate
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    fun animateNavigation(action: suspend () -> Unit) {
        navigationJob?.cancel()
        navigationJob = scope.launch { action() }
    }
    val gridPan = remember(s.id) { MutableStateFlow(0) }
    val canCollapseGaps = remember(s) { GridTimeline.canCollapseGaps(s) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val dragMetrics = remember(s.id) { GridDragMetrics() }
    var dragging by remember { mutableStateOf<DraggingCourse?>(null) }
    var pendingConflict by remember { mutableStateOf<PendingDragConflict?>(null) }
    LaunchedEffect(weekPager) {
        snapshotFlow { weekPager.currentPage }
            .collect { page ->
                if (
                    !daily &&
                        CourseEngine.week(s, LocalDate.parse(weekSelectedDate))
                            .coerceIn(1, s.maxWeeks) != page + 1
                ) {
                    weekSelectedDate =
                        CourseEngine.dateAt(s, page + 1, selectedWeekday + 1).toString()
                }
            }
    }
    val edgeMarginPx = with(density) { 32.dp.toPx() }
    LaunchedEffect(dragging != null, dragging?.fingerInRoot) {
        val current = dragging ?: return@LaunchedEffect
        val relX = current.fingerInRoot.x - dragMetrics.viewportPosInRoot.x
        val vw = dragMetrics.viewportWidthPx
        if (vw <= 0f) return@LaunchedEffect
        val dir =
            when {
                relX < edgeMarginPx && weekPager.currentPage > 0 -> -1
                relX > vw - edgeMarginPx && weekPager.currentPage < s.maxWeeks - 1 -> 1
                else -> 0
            }
        if (dir != 0) {
            delay(500L)
            val targetPage = (weekPager.currentPage + dir).coerceIn(0, s.maxWeeks - 1)
            if (targetPage != weekPager.currentPage) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                weekPager.animateScrollToPage(targetPage)
            }
        }
    }
    fun dayPage(date: LocalDate) =
        (DAY_PAGER_ANCHOR.toLong() + date.toEpochDay() - dayAnchor)
            .coerceIn(0, DAY_PAGER_COUNT.toLong() - 1)
            .toInt()
    fun showDay(date: LocalDate) {
        navigationJob?.cancel()
        // Request the starting position before composing the previously hidden pager.
        // scrollToPage would wait for its first layout and prevent the mode switch.
        dayPager.requestScrollToPage(dayPage(date))
        daily = true
    }
    fun moveDate(days: Long) {
        animateNavigation { dayPager.animateScrollToPage(dayPage(dayDate.plusDays(days))) }
    }
    fun moveWeek(target: Int) {
        val targetWeek = target.coerceIn(1, s.maxWeeks)
        animateNavigation {
            if (daily) {
                val date =
                    LocalDate.parse(s.week1Monday)
                        .plusWeeks((targetWeek - 1).toLong())
                        .plusDays((dayDate.dayOfWeek.value - 1).toLong())
                dayPager.animateScrollToPage(dayPage(date))
            } else {
                weekSelectedDate =
                    CourseEngine.dateAt(s, targetWeek, selectedWeekday + 1).toString()
                weekPager.animateScrollToPage(targetWeek - 1)
            }
        }
    }
    fun jumpToday() {
        animateNavigation {
            selectedWeekday = dateNow.dayOfWeek.value - 1
            if (daily) dayPager.animateScrollToPage(dayPage(dateNow))
            else weekPager.animateScrollToPage(actualWeek.coerceIn(1, s.maxWeeks) - 1)
            weekSelectedDate = dateNow.toString()
        }
    }
    val showReturn =
        if (daily) dayDate != dateNow
        else weekPager.currentPage != actualWeek.coerceIn(1, s.maxWeeks) - 1
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimetableHeader(
                s = s,
                week = week,
                schedules = schedules,
                daily = daily,
                onWeekSelected = ::moveWeek,
                onPrevWeek = { moveWeek(week - 1) },
                onNextWeek = { moveWeek(week + 1) },
                onToday = ::jumpToday,
                onToggleMode = {
                    if (daily) {
                        navigationJob?.cancel()
                        selectedWeekday = dayDate.dayOfWeek.value - 1
                        weekSelectedDate = dayDate.toString()
                        weekPager.requestScrollToPage(
                            CourseEngine.week(s, dayDate).coerceIn(1, s.maxWeeks) - 1
                        )
                        daily = false
                    } else showDay(LocalDate.parse(selectedDate))
                },
                onSelectSchedule = onSelectSchedule,
                nav = nav,
            )
            NextClassBar(s, now, p, nav)
            if (actualWeek !in 1..s.maxWeeks) Muted(if (actualWeek < 1) "学期尚未开始" else "本学期已结束")
            if (daily) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = {
                            moveDate(-1)
                        }
                    ) {
                        Text("前一天")
                    }
                    Text(
                        "$selectedDate ${weekdays[LocalDate.parse(selectedDate).dayOfWeek.value - 1]}",
                        Modifier.align(Alignment.CenterVertically),
                        fontSize = 13.sp,
                    )
                    TextButton(
                        onClick = {
                            moveDate(1)
                        }
                    ) {
                        Text("后一天")
                    }
                }
                Row(Modifier.weight(1f)) {
                    TimetablePager(dayPager, Modifier.weight(1f).fillMaxHeight().testTag("day-list")) {
                        page ->
                        DayList(
                            s,
                            LocalDate.ofEpochDay(dayAnchor + page - DAY_PAGER_ANCHOR),
                            now,
                            nav,
                            Modifier.fillMaxSize(),
                            p,
                        )
                    }
                    Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        if (showReturn) ReturnToTodayButton(true, Modifier) { jumpToday() }
                    }
                }
            } else {
                Row(Modifier.weight(1f)) {
                    TimetablePager(
                        weekPager,
                        Modifier.weight(1f).fillMaxHeight().testTag("week-viewport"),
                        userScrollEnabled = dragging == null,
                    ) { page ->
                        WeekGrid(
                            s,
                            p,
                            page + 1,
                            dateNow,
                            gridPan,
                            weekPager.settledPage == page,
                            canCollapseGaps,
                            nav,
                            now = now,
                            dragMetrics = dragMetrics,
                            dragging = dragging,
                            onDragStart = { occ, fromW, wPx, hPx, finger, grab ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val (td, ts, te) = computeDropTarget(dragMetrics, occ, finger)
                                dragging =
                                    DraggingCourse(
                                        occurrence = occ,
                                        fromWeek = fromW,
                                        widthPx = wPx,
                                        heightPx = hPx,
                                        fingerInRoot = finger,
                                        grabOffsetInCard = grab,
                                        targetDay = td,
                                        targetStartSection = ts,
                                        targetEndSection = te,
                                    )
                            },
                            onDragDelta = { finger ->
                                val cur = dragging ?: return@WeekGrid
                                val (td, ts, te) = computeDropTarget(dragMetrics, cur.occurrence, finger)
                                dragging =
                                    cur.copy(
                                        fingerInRoot = finger,
                                        targetDay = td,
                                        targetStartSection = ts,
                                        targetEndSection = te,
                                    )
                            },
                            onDragEnd = {
                                val cur = dragging
                                dragging = null
                                if (cur != null) {
                                    val targetWeek = weekPager.currentPage + 1
                                    val sameSlot =
                                        targetWeek == cur.fromWeek &&
                                            cur.targetDay == cur.occurrence.session.day &&
                                            cur.targetStartSection == cur.occurrence.session.startSection &&
                                            cur.targetEndSection == cur.occurrence.session.endSection
                                    if (!sameSlot) {
                                        val conflicts =
                                            CourseEngine.slotConflicts(
                                                s,
                                                targetWeek,
                                                cur.targetDay,
                                                cur.targetStartSection,
                                                cur.targetEndSection,
                                                excludeSessionId = cur.occurrence.session.id,
                                            )
                                        if (conflicts.isEmpty()) {
                                            val updated =
                                                CourseEngine.moveSessionForWeek(
                                                    s,
                                                    cur.occurrence.session.id,
                                                    cur.fromWeek,
                                                    targetWeek,
                                                    cur.targetDay,
                                                    cur.targetStartSection,
                                                    cur.targetEndSection,
                                                )
                                            onUpdateSchedule(updated)
                                        } else {
                                            pendingConflict =
                                                PendingDragConflict(
                                                    source = cur.occurrence,
                                                    fromWeek = cur.fromWeek,
                                                    targetWeek = targetWeek,
                                                    targetDay = cur.targetDay,
                                                    targetStartSection = cur.targetStartSection,
                                                    targetEndSection = cur.targetEndSection,
                                                    conflicts = conflicts,
                                                )
                                        }
                                    }
                                }
                            },
                            onDragCancel = { dragging = null },
                        ) { date ->
                            selectedWeekday = date.dayOfWeek.value - 1
                            showDay(date)
                        }
                    }
                    Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        if (showReturn) ReturnToTodayButton(false, Modifier) { jumpToday() }
                    }
                }
            }
            WeekNotesBar(s, week, monday, p)
            if (p.showWeekProgress) WeekScrubber(week, s.maxWeeks, actualWeek, ::moveWeek)
        }
        DragHintChip(dragging, week)
        DragPreviewCard(dragging, p, dragMetrics.viewportPosInRoot)
    }
    pendingConflict?.let { conflict ->
        DragConflictDialog(
            conflict = conflict,
            s = s,
            onSwap = { other ->
                onUpdateSchedule(
                    CourseEngine.swapSessionsForWeek(
                        s,
                        conflict.source.session.id,
                        conflict.fromWeek,
                        other.id,
                        conflict.targetWeek,
                    )
                )
                pendingConflict = null
            },
            onOverwrite = {
                onUpdateSchedule(
                    CourseEngine.overwriteSessionForWeek(
                        s,
                        conflict.source.session.id,
                        conflict.fromWeek,
                        conflict.targetWeek,
                        conflict.targetDay,
                        conflict.targetStartSection,
                        conflict.targetEndSection,
                    )
                )
                pendingConflict = null
            },
            onDismiss = { pendingConflict = null },
        )
    }
}