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
    val pager = rememberTimetablePagerState(s, anchor, initialWeek)
    val daily = pager.daily
    val week = pager.week
    val monday = pager.monday
    val selectedDate = pager.selectedDate
    val dayDate = pager.dayDate
    val gridPan = remember(s.id) { MutableStateFlow(0) }
    val canCollapseGaps = remember(s) { GridTimeline.canCollapseGaps(s) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val dragMetrics = remember(s.id) { GridDragMetrics() }
    var dragging by remember { mutableStateOf<DraggingCourse?>(null) }
    var pendingConflict by remember { mutableStateOf<PendingDragConflict?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.weekPager.currentPage }.collect(pager::syncSelectedDateWithPage)
    }
    val edgeMarginPx = with(density) { 32.dp.toPx() }
    LaunchedEffect(dragging != null, dragging?.fingerInRoot) {
        val current = dragging ?: return@LaunchedEffect
        pager.autoPageTowardEdge(
            relativeX = current.fingerInRoot.x - dragMetrics.viewportPosInRoot.x,
            viewportWidthPx = dragMetrics.viewportWidthPx,
            edgePx = edgeMarginPx,
            onPaging = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        )
    }
    val showReturn =
        if (daily) dayDate != dateNow
        else pager.weekPager.currentPage != actualWeek.coerceIn(1, s.maxWeeks) - 1
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
                showReturn = showReturn,
                onWeekSelected = pager::moveWeek,
                onPrevWeek = { pager.moveWeek(week - 1) },
                onNextWeek = { pager.moveWeek(week + 1) },
                onToday = { pager.jumpToday(dateNow) },
                onToggleMode = pager::toggleMode,
                onSelectSchedule = onSelectSchedule,
                nav = nav,
            )
            // 「日 | 周」摆到明面上：此前只有「更多」菜单里一个「切换为日列表」，
            // 藏在两层菜单下，多数人找不到，看着像没有日课表。默认仍是周课表。
            // 与「下一节」提示条同一行：没有下一节课时这一行就只有切换控件，不会白占高度。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TimetableModeSwitch(daily = daily) { pager.toggleMode() }
                Box(Modifier.weight(1f)) { NextClassBar(s, now, p, nav) }
            }
            if (actualWeek !in 1..s.maxWeeks) Muted(if (actualWeek < 1) "学期尚未开始" else "本学期已结束")
            if (daily) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = {
                            pager.moveDate(-1)
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
                            pager.moveDate(1)
                        }
                    ) {
                        Text("后一天")
                    }
                }
                Row(Modifier.weight(1f)) {
                    TimetablePager(pager.dayPager, Modifier.weight(1f).fillMaxHeight().testTag("day-list")) {
                        page ->
                        DayList(
                            s,
                            pager.dateOfDayPage(page),
                            now,
                            nav,
                            Modifier.fillMaxSize(),
                            p,
                        )
                    }
                }
            } else {
                Row(Modifier.weight(1f)) {
                    TimetablePager(
                        pager.weekPager,
                        Modifier.weight(1f).fillMaxHeight().testTag("week-viewport"),
                        userScrollEnabled = dragging == null,
                    ) { page ->
                        WeekGrid(
                            s,
                            p,
                            page + 1,
                            dateNow,
                            gridPan,
                            pager.weekPager.settledPage == page,
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
                                    val targetWeek = pager.weekPager.currentPage + 1
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
                            pager.selectWeekday(date)
                            pager.showDay(date)
                        }
                    }
                }
            }
            WeekNotesBar(s, week, monday, p)
            if (p.showWeekProgress) WeekScrubber(week, s.maxWeeks, actualWeek) { pager.moveWeek(it) }
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