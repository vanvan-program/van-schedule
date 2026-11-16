// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.van.timetable.domain.*
import app.van.timetable.ui.nexio.*
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.shape.ContinuousCapsule
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import java.time.*
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Welcome(vm: VanViewModel, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("van课表", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("每一天，都有清楚的安排。", fontSize = 20.sp)
        Muted("课程保存在本机 · 无广告 · 开源")
        Spacer(Modifier.height(12.dp))
        Action("从 WakeUp 迁移", "备份和 CSV 本地导入") { nav("import") }
        Action("从教务系统导入", "输入教务系统地址，在学校网页中获取课程") { nav("school") }
        Action("手动创建课表", "设置学期、作息并添加课程") { nav("newSchedule") }
        OutlinedButton(onClick = { vm.demo() }, modifier = Modifier.fillMaxWidth()) {
            Text("先查看示例课表")
        }
        Muted("示例数据不会替代你的真实课程。可在课表管理中删除。")
    }
}

private data class PendingDragConflict(
    val source: Occurrence,
    val fromWeek: Int,
    val targetWeek: Int,
    val targetDay: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
    val conflicts: List<CourseSession>,
)

@OptIn(ExperimentalFoundationApi::class)
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
    var selectWeek by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scheduleDropdown by remember { mutableStateOf(false) }
    val gridPan = remember(s.id) { MutableStateFlow(0) }
    val canCollapseGaps = remember(s) { GridTimeline.canCollapseGaps(s) }
    val colors = LocalVan.current
    val backdrop = LocalVanBackdrop.current
    val materialLevel = LocalVanMaterialLevel.current
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
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { selectWeek = true },
                    modifier = Modifier.testTag("week-selector"),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    MotionText(
                        "第${week}周 ▾",
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(ContinuousRoundedRectangle(12.dp))
                            .clickable {
                                if (schedules.size > 1) scheduleDropdown = true
                                else nav("schedules")
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MotionText(
                            s.name,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (schedules.size > 1) {
                            Text("▾", fontSize = 11.sp, color = colors.muted)
                        }
                    }
                    if (scheduleDropdown) {
                        NexioGlassDropdownPanel(
                            expanded = scheduleDropdown,
                            onDismiss = { scheduleDropdown = false },
                            backdrop = backdrop,
                            materialLevel = materialLevel,
                            isDark = colors.dark,
                            surfaceColor = colors.surface,
                        ) {
                            schedules.forEach { item ->
                                val isCurrent = item.id == s.id
                                NexioGlassDropdownItem(
                                    label = if (isCurrent) "${item.name} ✓" else item.name,
                                    selected = isCurrent,
                                    highlightColor = colors.blue,
                                ) {
                                    scheduleDropdown = false
                                    if (!isCurrent) onSelectSchedule(item.id)
                                }
                            }
                            NexioGlassDropdownItem(
                                label = "管理全部课表…",
                                selected = false,
                                highlightColor = colors.muted,
                            ) {
                                scheduleDropdown = false
                                nav("schedules")
                            }
                        }
                    }
                }
                IconButton(onClick = { nav("import") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.FileUpload, "导入课表", Modifier.size(22.dp))
                }
                IconButton(onClick = { nav("edit:new") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Add, "添加课程", Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "课表菜单", Modifier.size(22.dp))
                    }
                    if (menu) {
                        NexioGlassDropdownPanel(
                            expanded = menu,
                            onDismiss = { menu = false },
                            backdrop = backdrop,
                            materialLevel = materialLevel,
                            isDark = colors.dark,
                            surfaceColor = colors.surface,
                            alignment = Alignment.TopEnd,
                        ) {
                            NexioGlassDropdownItem(label = "上一周", enabled = week > 1) {
                                moveWeek(week - 1)
                                menu = false
                            }
                            NexioGlassDropdownItem(label = "下一周", enabled = week < s.maxWeeks) {
                                moveWeek(week + 1)
                                menu = false
                            }
                            NexioGlassDropdownItem(label = "回到本周") {
                                jumpToday()
                                menu = false
                            }
                            NexioGlassDropdownItem(label = if (daily) "切换为周课表" else "切换为日列表") {
                                if (daily) {
                                    navigationJob?.cancel()
                                    selectedWeekday = dayDate.dayOfWeek.value - 1
                                    weekSelectedDate = dayDate.toString()
                                    weekPager.requestScrollToPage(
                                        CourseEngine.week(s, dayDate).coerceIn(1, s.maxWeeks) - 1
                                    )
                                    daily = false
                                } else showDay(LocalDate.parse(selectedDate))
                                menu = false
                            }
                            NexioGlassDropdownItem(label = "课表管理") {
                                menu = false
                                nav("schedules")
                            }
                            NexioGlassDropdownItem(label = "外观设置") {
                                menu = false
                                nav("appearance")
                            }
                        }
                    }
                }
            }
            // Searching future days must not block a drag or repeat on page changes.
            val nextState =
                produceState<Occurrence?>(null, s, now, p.globalDayOverrides) {
                    value = withContext(Dispatchers.Default) { CourseEngine.next(s, now, p.globalDayOverrides) }
                }
        val next = nextState.value
        if (next != null) {
            Surface(
                Modifier.fillMaxWidth().testTag("next-class").clickable {
                    nav(occurrenceRoute(next))
                },
                shape = RoundedCornerShape(8.dp),
                color = colors.surface.copy(alpha = 0.65f),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Outlined.NotificationsNone,
                        null,
                        Modifier.size(14.dp),
                        tint = colors.blue,
                    )
                    Text(
                        (if (now >= next.startsAt(ZoneId.of(s.zone))) "正在上课 · " else "下一节 · ") +
                            next.course.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (now >= next.startsAt(ZoneId.of(s.zone))) "进行中 · 至${next.end}"
                        else
                            "${if (next.date == dateNow) "今天" else if (next.date == dateNow.plusDays(1)) "明天" else "${next.date.monthValue}/${next.date.dayOfMonth}"} ${next.start}",
                        color = colors.blue,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
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
        val notes =
            CourseEngine.special(s, week) +
                s.holidays
                    .filter {
                        LocalDate.parse(it.from) <= monday.plusDays(6) &&
                            LocalDate.parse(it.through) >= monday
                    }
                    .map { "${it.name}：${it.from} 至 ${it.through}" } +
                (s.dayOverrides + p.globalDayOverrides)
                    .distinctBy { it.date }
                    .filter { LocalDate.parse(it.date) in monday..monday.plusDays(6) }
                    .map {
                        if (it.cancelled) "${it.date} 停课" else "${it.date} 上 ${it.sourceDate} 的课"
                    } +
                s.sessionOverrides
                    .filter { LocalDate.parse(it.originalDate) in monday..monday.plusDays(6) }
                    .map { "${it.originalDate} 有单次调课" }
        // Keep the viewport identical while paging through weeks with different notes.
        Box(
            Modifier.fillMaxWidth()
                .height((20f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
                .testTag("week-notes"),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (notes.isNotEmpty())
                Text(
                    notes.joinToString("；"),
                    Modifier.fillMaxWidth().basicMarquee(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    color = colors.muted,
                )
        }
        if (p.showWeekProgress) WeekScrubber(week, s.maxWeeks, actualWeek, ::moveWeek)
        }
        AnimatedVisibility(
            visible = dragging != null,
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.9f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
        ) {
            dragging?.let { d ->
                val shape = ContinuousCapsule()
                val text =
                    if (d.fromWeek != week) "第${d.fromWeek}周 → 第${week}周 · ${weekdays[d.targetDay - 1]} ${d.targetStartSection}–${d.targetEndSection}节"
                    else "调至 ${weekdays[d.targetDay - 1]} ${d.targetStartSection}–${d.targetEndSection}节（拖向左右边缘可跨周）"
                Row(
                    Modifier.then(
                        if (backdrop != null && materialLevel != GlassMaterialLevel.NONE) {
                            Modifier.vanChromeGlass(
                                backdrop = backdrop,
                                shape = shape,
                                level = materialLevel,
                                isDark = colors.dark,
                                fallbackColor = colors.blue.copy(alpha = 0.85f),
                                tint = colors.blue,
                            )
                        } else {
                            Modifier.background(colors.blue, shape)
                        }
                    ).padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        dragging?.let { d ->
            val rootPos = dragMetrics.viewportPosInRoot
            val cardColor = colors.courseColor(d.occurrence.course)
            val cardShape = ContinuousRoundedRectangle(p.cardCornerDp.coerceIn(0f, 28f).dp)
            val cardWidthDp = with(density) { d.widthPx.toDp() }
            val cardHeightDp = with(density) { d.heightPx.toDp() }
            val topLeftX = d.fingerInRoot.x - d.grabOffsetInCard.x - rootPos.x
            val topLeftY = d.fingerInRoot.y - d.grabOffsetInCard.y - rootPos.y
            Box(
                Modifier.offset { IntOffset(topLeftX.roundToInt(), topLeftY.roundToInt()) }
                    .size(cardWidthDp, cardHeightDp)
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                    }
                    .shadow(14.dp, cardShape)
                    .then(
                        if (backdrop != null && materialLevel != GlassMaterialLevel.NONE) {
                            Modifier.vanSoftGlassBackdrop(
                                backdrop = backdrop,
                                shape = cardShape,
                                style = p.toCardSoftGlassStyle(colors.dark, cardColor),
                                radiusPx = p.cardCornerDp * 2.5f,
                                keySuffix = "DragCard",
                            )
                        } else {
                            Modifier.background(cardColor.copy(alpha = 0.94f), cardShape)
                        }
                    )
                    .border(
                        1.5.dp,
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.95f), cardColor.copy(alpha = 0.5f)),
                            center = d.grabOffsetInCard,
                            radius = maxOf(d.widthPx, d.heightPx),
                        ),
                        cardShape,
                    )
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.42f), Color.Transparent),
                            center = d.grabOffsetInCard,
                            radius = maxOf(d.widthPx, d.heightPx) * 0.9f,
                        ),
                        cardShape,
                    )
                )
                Column(
                    Modifier.fillMaxSize().padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        d.occurrence.course.name,
                        color = courseTextColor(cardColor),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (d.occurrence.place.isNotBlank()) {
                        Text(
                            d.occurrence.place,
                            color = courseTextColor(cardColor).copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    if (selectWeek)
        AlertDialog(
            onDismissRequest = { selectWeek = false },
            shape = ContinuousRoundedRectangle(24.dp),
            title = { Text("选择周次") },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items((1..s.maxWeeks).toList()) { w ->
                        TextButton(
                            onClick = {
                                moveWeek(w)
                                selectWeek = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (w == week) "✓ 第${w}周" else "第${w}周")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectWeek = false }) { Text("关闭") } },
        )
    pendingConflict?.let { conflict ->
        val conflictNames =
            conflict.conflicts.mapNotNull { cs ->
                s.courses.firstOrNull { it.id == cs.courseId }?.name
            }.distinct().joinToString("、")
        val canSwap =
            conflict.conflicts.size == 1 &&
                conflict.conflicts.first().mode == TimeMode.SECTIONS &&
                conflict.conflicts.first().date == null
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            shape = ContinuousRoundedRectangle(24.dp),
            title = { Text("课程时间冲突") },
            text = {
                Text(
                    "第${conflict.targetWeek}周 ${weekdays[conflict.targetDay - 1]} 第${conflict.targetStartSection}–${conflict.targetEndSection}节已有《${conflictNames.ifBlank { "其他课程" }}》，请选择处理方式："
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canSwap) {
                        TextButton(onClick = {
                            val updated =
                                CourseEngine.swapSessionsForWeek(
                                    s,
                                    conflict.source.session.id,
                                    conflict.fromWeek,
                                    conflict.conflicts.first().id,
                                    conflict.targetWeek,
                                )
                            onUpdateSchedule(updated)
                            pendingConflict = null
                        }) { Text("互换位置") }
                    }
                    Button(onClick = {
                        val updated =
                            CourseEngine.overwriteSessionForWeek(
                                s,
                                conflict.source.session.id,
                                conflict.fromWeek,
                                conflict.targetWeek,
                                conflict.targetDay,
                                conflict.targetStartSection,
                                conflict.targetEndSection,
                            )
                        onUpdateSchedule(updated)
                        pendingConflict = null
                    }) { Text("覆盖原课程") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConflict = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NexioGlassDropdownPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    backdrop: com.haooz.softglass.backdrop.GlassBackdrop?,
    materialLevel: GlassMaterialLevel,
    isDark: Boolean,
    surfaceColor: Color,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dragSelect = remember {
        DropdownPanelDragSelectState(
            axis = DragSelectAxis.Vertical,
            maskMode = DropdownMaskMode.Sliding,
        )
    }
    val haptic = LocalHapticFeedback.current
    val shape = ContinuousRoundedRectangle(18.dp)
    androidx.compose.ui.window.Popup(
        alignment = alignment,
        onDismissRequest = onDismiss,
    ) {
        val visibleScale by animateFloatAsState(
            targetValue = if (expanded) 1f else 0.88f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 480f),
            label = "dropdownScale",
        )
        CompositionLocalProvider(LocalDropdownPanelDragSelect provides dragSelect) {
            Column(
                Modifier.widthIn(min = 176.dp, max = 236.dp)
                    .graphicsLayer {
                        scaleX = visibleScale
                        scaleY = visibleScale
                        transformOrigin =
                            if (alignment == Alignment.TopEnd) TransformOrigin(1f, 0f)
                            else TransformOrigin(0f, 0f)
                    }
                    .shadow(12.dp, shape)
                    .then(
                        if (backdrop != null && materialLevel != GlassMaterialLevel.NONE) {
                            Modifier.vanChromeGlass(
                                backdrop = backdrop,
                                shape = shape,
                                level = materialLevel,
                                isDark = isDark,
                                fallbackColor = surfaceColor.copy(alpha = 0.9f),
                            )
                        } else {
                            Modifier.background(surfaceColor, shape)
                        }
                    )
                    .dropdownPanelSlidingMask(dragSelect, ContinuousRoundedRectangle(12.dp))
                    .dropdownPanelDragSelect(dragSelect, { 1f }, haptic)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun NexioGlassDropdownItem(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    highlightColor: Color = LocalVan.current.blue,
    onClick: () -> Unit,
) {
    val colors = LocalVan.current
    Box(
        Modifier.fillMaxWidth()
            .clip(ContinuousRoundedRectangle(12.dp))
            .dropdownPanelEntry(
                enabled = enabled,
                selected = selected,
                action = onClick,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color =
                when {
                    !enabled -> colors.muted.copy(alpha = 0.45f)
                    selected -> highlightColor
                    else -> colors.text
                },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReturnToTodayButton(daily: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = if (daily) "回到今日" else "回到本周"
    Surface(
        onClick = onClick,
        modifier =
            modifier.width(44.dp).heightIn(min = 112.dp).testTag("jump-today").semantics {
                contentDescription = label
            },
        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
        color = LocalVan.current.blue,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Text(
            label.toCharArray().joinToString("\n"),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}


@Composable
fun DayList(
    s: Schedule,
    date: LocalDate,
    now: Instant,
    nav: (String) -> Unit,
    modifier: Modifier = Modifier,
    p: Preferences = Preferences(),
) {
    val list = remember(s, date, p.globalDayOverrides) {
        CourseEngine.timetableDay(s, date, p.globalDayOverrides)
    }
    val holiday = remember(s, date) { CourseEngine.holiday(s, date) }
    val conflicts = remember(list) { CourseEngine.conflicts(list).flatten().map { it.id }.toSet() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (list.isEmpty() || holiday != null)
            item {
                Panel {
                    Text(if (holiday == null) "当天无课" else "放假期间，今日无课")
                    Muted(
                        holiday?.let { "${it.name} · ${it.from} 至 ${it.through}" }
                            ?: "可以添加课程或查看其他日期"
                    )
                }
            }
        if (p.groupDayListByPeriod && list.isNotEmpty()) {
            val morning = list.filter { it.start < LocalTime.of(12, 0) }
            val afternoon = list.filter { it.start >= LocalTime.of(12, 0) && it.start < LocalTime.of(18, 0) }
            val evening = list.filter { it.start >= LocalTime.of(18, 0) }
            listOf("上午" to morning, "下午" to afternoon, "晚上" to evening)
                .filter { it.second.isNotEmpty() }
                .forEach { (periodLabel, itemsInPeriod) ->
                    item(key = "period-$periodLabel") {
                        Text(
                            "$periodLabel（${itemsInPeriod.size}）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalVan.current.muted,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                    items(itemsInPeriod, key = { it.id }) { o ->
                        CourseRow(
                            o,
                            now,
                            { nav(occurrenceRoute(o)) },
                            ZoneId.of(s.zone),
                            holiday == null && o.id in conflicts,
                            inactive = holiday != null,
                        )
                    }
                }
        } else {
            items(list, key = { it.id }) { o ->
                CourseRow(
                    o,
                    now,
                    { nav(occurrenceRoute(o)) },
                    ZoneId.of(s.zone),
                    holiday == null && o.id in conflicts,
                    inactive = holiday != null,
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun Courses(s: Schedule, now: Instant, nav: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyWeek by rememberSaveable { mutableStateOf(false) }
    val today = now.atZone(ZoneId.of(s.zone)).toLocalDate()
    val week = CourseEngine.week(s, today)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "课程",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { nav("notes") },
                modifier = Modifier.testTag("all-course-notes-button"),
            ) {
                Icon(Icons.Outlined.EditNote, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("课程备注")
            }
            IconButton(onClick = { nav("edit:new") }) { Icon(Icons.Outlined.Add, "新增课程") }
        }
        Field(query, { query = it }, "搜索课程、老师、地点")
        Toggle("只看本周有课", onlyWeek, { onlyWeek = it })
        val groups = remember(s) { s.courseGroups() }
        val filtered =
            remember(groups, s, query, onlyWeek, week, currentMonday) {
                groups
                    .filter { group ->
                        val ids = group.members.map { it.id }.toSet()
                        val sessions = s.sessions.filter { it.courseId in ids }
                        (!onlyWeek || sessions.any { n ->
                            n.date?.let { runCatching { LocalDate.parse(it) in currentMonday..currentMonday.plusDays(6) }.getOrDefault(false) }
                                ?: (week in n.weeks)
                        }) && (group.members.any { it.name.contains(query, true) || it.note.contains(query, true) } ||
                            sessions.any { it.teacher.contains(query, true) || it.place.contains(query, true) })
                    }
                    .sortedWith { a, b -> NaturalCourseSort.compare(a.primary.name, b.primary.name) }
            }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item { Muted("没有符合条件的课程") }
            items(filtered, key = { it.primary.id }) { group ->
                val c = group.primary
                Panel(Modifier.testTag("course-group-${c.id}").clickable { nav("detail:${c.id}") }) {
                    Row {
                        Box(
                            Modifier.size(12.dp)
                                .background(
                                    LocalVan.current.courseColor(c),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                    }
                    if (group.members.size > 1) Muted(group.members.map { it.groupSectionLabel() }.distinct().joinToString(" · "))
                    s.sessions
                        .filter { n -> group.members.any { it.id == n.courseId } }
                        .forEach { n ->
                            if (n.date == null) Muted("周次：${formatWeeks(n.weeks)}")
                            Muted(
                                "${if(n.date!=null)n.date else if(n.day in 1..7)weekdays[n.day-1]else"整周"} · ${if(n.mode==TimeMode.SECTIONS)"${n.startSection}–${n.endSection}节"else if(n.mode==TimeMode.CLOCK)"${n.start}–${n.end}"else"无固定时间"} · ${n.place}"
                            )
                        }
                }
            }
        }
    }
}
