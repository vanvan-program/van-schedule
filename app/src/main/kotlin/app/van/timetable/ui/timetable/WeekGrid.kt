// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.van.timetable.domain.*
import app.van.timetable.ui.*
import app.van.timetable.ui.nexio.*
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import java.time.*
import kotlinx.coroutines.flow.MutableStateFlow

/** 展开空档时，一小时空档占一行高度的比例（再按时长缩放并封顶）。 */
private const val ExpandedGapUnits = 0.42f

/** 20 分钟以内的课间只留一条细缝，避免十几个小空档把课表撑长。 */
private const val ShortGapUnits = 0.05f

internal class GridDragMetrics(
    var viewportPosInRoot: Offset = Offset.Zero,
    var viewportWidthPx: Float = 0f,
    var gutterPx: Float = 0f,
    var headerHeightPx: Float = 0f,
    var dayWidthPx: Float = 1f,
    var rowHeightPx: Float = 1f,
    var days: Int = 7,
    var scrollX: Int = 0,
    var scrollY: Int = 0,
    var sections: List<Section> = emptyList(),
    var timeline: GridTimeline? = null,
)

internal data class DraggingCourse(
    val occurrence: Occurrence,
    val fromWeek: Int,
    val widthPx: Float,
    val heightPx: Float,
    val fingerInRoot: Offset,
    val grabOffsetInCard: Offset,
    val targetDay: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
)

internal fun computeDropTarget(
    metrics: GridDragMetrics,
    source: Occurrence,
    fingerInRoot: Offset,
): Triple<Int, Int, Int> {
    val localX = fingerInRoot.x - metrics.viewportPosInRoot.x + metrics.scrollX - metrics.gutterPx
    val localY = fingerInRoot.y - metrics.viewportPosInRoot.y - metrics.headerHeightPx + metrics.scrollY
    val day = (localX / metrics.dayWidthPx.coerceAtLeast(1f)).toInt().coerceIn(0, metrics.days - 1) + 1
    val span = (source.session.endSection - source.session.startSection).coerceAtLeast(0)
    val maxSection = metrics.sections.size.coerceAtLeast(1)
    val approxUnit = (localY / metrics.rowHeightPx.coerceAtLeast(1f))
    val sections = metrics.sections
    val startSection =
        if (sections.isEmpty()) 1
        else {
            val timeline = metrics.timeline
            if (timeline != null) {
                sections.minByOrNull { sec ->
                    val pos = timeline.position(LocalTime.parse(sec.start))
                    kotlin.math.abs(pos - approxUnit)
                }?.number ?: 1
            } else {
                (approxUnit.toInt() + 1).coerceIn(1, maxSection)
            }
        }
    val clampedStart = startSection.coerceIn(1, (maxSection - span).coerceAtLeast(1))
    val clampedEnd = (clampedStart + span).coerceAtMost(maxSection)
    return Triple(day, clampedStart, clampedEnd)
}

internal fun occurrenceRoute(o: Occurrence) =
    "detail:${o.course.id}:${o.date}:${o.session.id}:${CourseEngine.originalDate(o)}"

private data class GridEntry(val occurrence: Occurrence, val inactive: Boolean, val prefix: String, val conflict: Boolean)
private data class GridDay(val date: LocalDate, val entries: List<GridEntry>)
private data class PlacedEntry(val entry: GridEntry, val top: Dp, val height: Dp, val compact: Boolean, val lane: Int, val lanes: Int)

/** Expensive course/conflict filtering is performed once per schedule/week/settings. */
private fun weekData(
    s: Schedule,
    monday: LocalDate,
    days: Int,
    showInactive: Boolean,
    globalDayOverrides: List<DayOverride>,
): List<GridDay> =
    (0 until days).map { d ->
        val date = monday.plusDays(d.toLong())
        val holiday = CourseEngine.holiday(s, date) != null
        val present = CourseEngine.timetableDay(s, date, globalDayOverrides)
        val groups = CourseEngine.conflicts(present)
        val groupById = groups.flatMap { group -> group.map { it.id to group } }.toMap()
        val mains = groups.map { CourseEngine.main(it).id }.toSet()
        val entries = present.filter { it.id !in groupById || it.id in mains }.map { o ->
            val group = groupById[o.id]
            GridEntry(o, holiday, "${if (holiday) "[放假]" else ""}${if (o.adjusted) "【调】" else ""}${if (group != null) "+${group.size - 1} " else ""}", group != null)
        }.toMutableList()
        if (showInactive) {
            val week = CourseEngine.week(s, date)
            val sections = CourseEngine.routine(s, date).sections.associateBy { it.number }
            val courses = s.courses.associateBy { it.id }
            s.sessions.filter { it.date == null && it.mode == TimeMode.SECTIONS && it.day == d + 1 && week !in it.weeks }
                .distinctBy { it.startSection to it.endSection }.forEach { n ->
                    val start = LocalTime.parse(sections.getValue(n.startSection).start)
                    val end = LocalTime.parse(sections.getValue(n.endSection).end)
                    if (present.none { start < it.end && end > it.start }) {
                        entries += GridEntry(Occurrence("${n.id}@$date", courses.getValue(n.courseId), n, date, start, end, n.place), true, "[非本周]", false)
                    }
                }
        }
        GridDay(date, entries)
    }

@Composable
internal fun WeekGrid(
    s: Schedule, p: Preferences, week: Int, dateNow: LocalDate,
    gridPan: MutableStateFlow<Int>, activePage: Boolean, canCollapseGaps: Boolean,
    nav: (String) -> Unit,
    now: Instant = Instant.now(),
    dragMetrics: GridDragMetrics? = null,
    dragging: DraggingCourse? = null,
    onDragStart: (Occurrence, Int, Float, Float, Offset, Offset) -> Unit = { _, _, _, _, _, _ -> },
    onDragDelta: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDaySelected: (LocalDate) -> Unit,
) {
    val monday = remember(s.week1Monday, week) { LocalDate.parse(s.week1Monday).plusWeeks((week - 1).toLong()) }
    val colors = LocalVan.current
    val days = if (p.showWeekend) 7 else 5
    val gridScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val nowLocalTime = remember(now, s.zone) { now.atZone(ZoneId.of(s.zone)).toLocalTime() }
    LaunchedEffect(gridScroll, activePage) {
        gridScroll.scrollTo(gridPan.value)
        if (activePage) snapshotFlow { gridScroll.value }.collect { gridPan.value = it }
        else gridPan.collect { if (gridScroll.value != it) gridScroll.scrollTo(it) }
    }
    val sections = remember(s, monday) { CourseEngine.routine(s, monday).sections }
    val data = remember(s, monday, days, p.showInactive, p.globalDayOverrides) {
        weekData(s, monday, days, p.showInactive, p.globalDayOverrides)
    }
    val compactAxis = p.showCourseGaps?.not() ?: canCollapseGaps
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 17.sp)
    val timeStyle = LocalTextStyle.current.copy(fontSize = 10.sp, lineHeight = 12.sp)
    // Measure using the same font and scale as the rendered labels. Fixed estimates
    // can leave the final time line only a few pixels high on a compact grid.
    val labels = remember(sections, textMeasurer, numberStyle, timeStyle) {
        sections.map { n ->
            Triple(
                textMeasurer.measure(n.label.ifBlank { n.number.toString() }, numberStyle, softWrap = false, maxLines = 1).size,
                textMeasurer.measure(n.start, timeStyle, softWrap = false, maxLines = 1).size,
                textMeasurer.measure(n.end, timeStyle, softWrap = false, maxLines = 1).size,
            )
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .testTag("week-grid-$week")
            .gridPagingGuard(gridScroll)
            .onGloballyPositioned { coords ->
                if (activePage && dragMetrics != null) {
                    dragMetrics.viewportPosInRoot = coords.positionInRoot()
                    dragMetrics.viewportWidthPx = coords.size.width.toFloat()
                }
            }
    ) {
        val density = LocalDensity.current
        val scale = density.fontScale.coerceAtLeast(1f)
        val labelWidth = with(density) { labels.maxOf { maxOf(it.first.width, it.second.width, it.third.width) }.toDp() }
        val labelHeight = with(density) { labels.maxOf { it.first.height + it.second.height + it.third.height }.toDp() }
        val gutter = maxOf(36.dp, labelWidth + 8.dp)
        val width = maxOf(maxWidth.value, 40f * days + gutter.value).dp
        val dayWidth = (width - gutter) / days
        val cardInsetX = 2.dp
        val cardInsetY = 2.dp
        val headerHeight = maxOf(40.dp, (34f * scale).dp)
        // 空档高度按时长分配：短课间留一条细缝，午休/晚休才占整块。
        // 以前每个空档都固定 24dp（含凌晨与深夜两端的空档），一屏十几个空档把课程行压得很扁。
        val gapUnitsByMinutes: (Long) -> Float = { minutes ->
            if (minutes >= 20L) (ExpandedGapUnits * minutes / 60f).coerceIn(0.2f, 0.9f)
            else ShortGapUnits
        }
        val occupied = remember(data) {
            data.flatMap { day -> day.entries.map { it.occurrence.start to it.occurrence.end } }.distinct()
        }
        val timeline = remember(sections, compactAxis, occupied) {
            if (compactAxis) GridTimeline.compact(sections, occupied)
            else GridTimeline.create(sections, ExpandedGapUnits, gapUnitsByMinutes, occupied, edgeGapUnits = ShortGapUnits)
        }
        val totalUnits = timeline.units.coerceAtLeast(1f)
        val fitted = (maxHeight - headerHeight - 8.dp) / totalUnits
        // Keep three aligned labels even in the 11- and 13-section routines.
        val labelScale = if (p.fitWeek && compactAxis)
            ((fitted - 4.dp) / labelHeight).coerceIn(0.8f, 1f) else 1f
        val minimumRowHeight = maxOf((36f * scale).dp, labelHeight * labelScale + 4.dp)
        val preferredRowHeight = maxOf(p.rowHeight.dp, minimumRowHeight)
        val rowHeight = if (p.fitWeek) minOf(preferredRowHeight, maxOf(minimumRowHeight, fitted)) else preferredRowHeight
        if (activePage && dragMetrics != null) {
            SideEffect {
                with(density) {
                    dragMetrics.gutterPx = gutter.toPx()
                    dragMetrics.headerHeightPx = headerHeight.toPx()
                    dragMetrics.dayWidthPx = dayWidth.toPx()
                    dragMetrics.rowHeightPx = rowHeight.toPx()
                }
                dragMetrics.days = days
                dragMetrics.scrollX = gridScroll.value
                dragMetrics.scrollY = verticalScroll.value
                dragMetrics.sections = sections
                dragMetrics.timeline = timeline
            }
        }
        val placed = remember(data, timeline, rowHeight) {
            data.map { day ->
                val gaps = day.entries.groupBy { timeline.gapContaining(it.occurrence.start, it.occurrence.end) }
                day.entries.map { entry ->
                val o = entry.occurrence
                val gap = timeline.gapContaining(o.start, o.end)
                val top = if (gap != null) rowHeight * timeline.position(gap.start) else rowHeight * timeline.position(o.start)
                val height = if (gap != null) rowHeight * gap.units else rowHeight * (timeline.position(o.end) - timeline.position(o.start))
                val neighbors = if (gap == null) listOf(entry) else gaps.getValue(gap)
                PlacedEntry(entry, top, height, gap != null || height < 28.dp, neighbors.indexOf(entry), neighbors.size)
            } }
        }
        val gapLabels = remember(data, timeline) {
            val clockEntries = data.flatMap { it.entries }.filter { entry ->
                !entry.inactive && (entry.occurrence.session.mode == TimeMode.CLOCK ||
                    s.sessionOverrides.any { it.sessionId == entry.occurrence.session.id &&
                        it.originalDate == CourseEngine.originalDate(entry.occurrence) && it.start != null })
            }
            timeline.bands.filter { it.section == null }.mapNotNull { band ->
                val occupants = clockEntries.map { it.occurrence }.filter { it.start < band.end && it.end > band.start }
                if (occupants.isEmpty()) null else band to
                    (maxOf(band.start, occupants.minOf { it.start }) to minOf(band.end, occupants.maxOf { it.end }))
            }
        }
        Column(Modifier.horizontalScroll(gridScroll).width(width)) {
            Row(Modifier.height(headerHeight)) {
                Spacer(Modifier.width(gutter))
                repeat(days) { d ->
                    val date = data[d].date
                    val isToday = date == dateNow
                    Column(
                        Modifier.width(dayWidth).fillMaxHeight()
                            .testTag("week-day-$week-$d")
                            .clickable { onDaySelected(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            weekdays[d],
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) colors.blue else colors.text,
                        )
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = if (isToday) colors.blue else colors.muted,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(verticalScroll)) {
                Box(Modifier.height(rowHeight * timeline.units).width(width)) {
                    sections.forEach { n ->
                        val secStart = LocalTime.parse(n.start)
                        val secEnd = LocalTime.parse(n.end)
                        val isCurrentSection = nowLocalTime >= secStart && nowLocalTime < secEnd
                        val secColor = if (isCurrentSection) colors.blue else colors.muted
                        Column(
                            Modifier.offset(y = rowHeight * timeline.position(secStart))
                                .width(gutter)
                                .height(rowHeight)
                                .testTag("section-${n.number}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                n.label.ifBlank { "${n.number}" },
                                fontSize = ((if (n.label.isNotBlank()) 12f else 14f) * labelScale).sp,
                                lineHeight = (17f * labelScale).sp,
                                fontWeight = if (isCurrentSection) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isCurrentSection) colors.blue else colors.text,
                            )
                            Text(n.start, fontSize = (10f * labelScale).sp, lineHeight = (12f * labelScale).sp, maxLines = 1, softWrap = false, color = secColor)
                            Text(n.end, fontSize = (10f * labelScale).sp, lineHeight = (12f * labelScale).sp, maxLines = 1, softWrap = false, color = secColor)
                        }
                    }
                    // 0.5.10 起不再画午休 / 晚休横带：空档本身就按时长留白，
                    // 再叠一条浅色带子只是多一层噪音。空档高度规则不受影响。
                    if (p.showCustomTimeLabels) gapLabels.forEach { (band, times) ->
                        Column(Modifier.offset(y = rowHeight * timeline.position(band.start)).width(gutter - 3.dp).height(rowHeight * band.units).testTag("clock-band-${times.first}-${times.second}"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                            Text(times.first.toString(), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false, color = colors.muted)
                            Text(times.second.toString(), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false, color = colors.muted)
                        }
                    }
                    if (activePage && dragging != null) {
                        val startSec = sections.firstOrNull { it.number == dragging.targetStartSection }
                        val endSec = sections.firstOrNull { it.number == dragging.targetEndSection }
                        if (startSec != null && endSec != null) {
                            val targetX by animateDpAsState(
                                targetValue = gutter + dayWidth * (dragging.targetDay - 1) + cardInsetX,
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = 750f),
                                label = "dropX",
                            )
                            val targetY by animateDpAsState(
                                targetValue = rowHeight * timeline.position(LocalTime.parse(startSec.start)) + cardInsetY,
                                animationSpec = spring(dampingRatio = 0.9f, stiffness = 750f),
                                label = "dropY",
                            )
                            val targetH =
                                maxOf(
                                    if (compactAxis) 24.dp else 1.dp,
                                    rowHeight * (timeline.position(LocalTime.parse(endSec.end)) - timeline.position(LocalTime.parse(startSec.start))) - cardInsetY * 2,
                                )
                            val dropShape = ContinuousRoundedRectangle(p.cardCornerDp.coerceIn(0f, 28f).dp)
                            Box(
                                Modifier.offset {
                                    with(density) { IntOffset(targetX.roundToPx(), targetY.roundToPx()) }
                                }
                                    .width(dayWidth - cardInsetX * 2)
                                    .height(targetH)
                                    .background(colors.blue.copy(alpha = 0.2f), dropShape)
                                    .border(1.5.dp, colors.blue, dropShape)
                            )
                        }
                    }
                    placed.forEachIndexed { d, entries -> entries.forEach { item ->
                        val entry = item.entry
                        val o = entry.occurrence
                        val color = if (entry.inactive) colors.inactive else colors.courseColor(o.course)
                        val cardWidth = (dayWidth - cardInsetX * 2) / item.lanes
                        val cardHeight = maxOf(1.dp, item.height - cardInsetY * 2)
                        val isBeingDragged = dragging?.occurrence?.session?.id == o.session.id && dragging.fromWeek == week
                        val canDrag = dragMetrics != null && !entry.inactive && !entry.conflict &&
                            o.session.mode == TimeMode.SECTIONS && o.session.date == null
                        var cardRootOffset = Offset.Zero
                        GridCard(
                            name = o.course.name,
                            place = o.place,
                            teacher = o.session.teacher,
                            color = color,
                            prefix = entry.prefix,
                            width = cardWidth,
                            height = cardHeight,
                            compact = item.compact,
                            inactive = entry.inactive,
                            hasWeekNote = !o.course.weekNotes[week].isNullOrBlank(),
                            p = p,
                            pastel = p.cardStyle != "solid",
                            modifier = Modifier.offset(x = gutter + dayWidth * d + cardInsetX + cardWidth * item.lane, y = item.top + cardInsetY)
                                // 只有正在被拖走的那张卡需要独立图层；其余卡片保持零图层，
                                // 翻页时不会一屏挂十几层离屏合成。
                                .then(
                                    if (isBeingDragged) Modifier.graphicsLayer { alpha = 0.25f }
                                    else Modifier
                                )
                                .then(
                                    if (canDrag) {
                                        Modifier.onGloballyPositioned { cardRootOffset = it.positionInRoot() }
                                            .pointerInput(o.id, week) {
                                                val wPx = with(density) { cardWidth.toPx() }
                                                val hPx = with(density) { cardHeight.toPx() }
                                                var finger = Offset.Zero
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { localOffset ->
                                                        finger = cardRootOffset + localOffset
                                                        onDragStart(o, week, wPx, hPx, finger, localOffset)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        finger += dragAmount
                                                        onDragDelta(finger)
                                                    },
                                                    onDragEnd = { onDragEnd() },
                                                    onDragCancel = { onDragCancel() },
                                                )
                                            }
                                    } else Modifier
                                )
                                .testTag(if (entry.inactive && entry.prefix == "[非本周]") "inactive-course-${o.id}" else if (entry.inactive) "holiday-course-${o.id}" else "grid-course-${o.id}"),
                        ) {
                            if (entry.conflict) onDaySelected(o.date)
                            else nav(occurrenceRoute(o))
                        }
                    } }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Width and height are known by the parent; avoid one subcomposition per card. */
@Composable
private fun GridCard(
    name: String,
    place: String,
    teacher: String,
    color: Color,
    prefix: String,
    width: Dp,
    height: Dp,
    compact: Boolean,
    inactive: Boolean,
    hasWeekNote: Boolean,
    p: Preferences,
    /** 柔和卡片：浅色底 + 同色系文字，整块不加玻璃、不描边。 */
    pastel: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalVan.current
    val glassSurface = LocalVanSoftGlassSurface.current
    val redrawSignal = LocalVanGlassRedrawSignal.current
    val materialLevel = LocalVanMaterialLevel.current
    val density = LocalDensity.current
    val textScale = p.cardTextScale.coerceIn(0.75f, 1.4f)
    val nameSize = (if (compact) 9f else if (width < 52.dp) 11f else if (pastel) 12.7f else 12f) * textScale
    val subSize = (if (pastel) 11f else 9f) * textScale
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = LocalTextStyle.current.copy(
        fontSize = nameSize.sp, lineHeight = (nameSize * 1.18f).sp,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    )
    val subStyle = LocalTextStyle.current.copy(
        fontSize = subSize.sp, lineHeight = (subSize * 1.2f).sp,
        textAlign = TextAlign.Center,
    )
    val textWidth = with(density) { (width - 6.dp).roundToPx().coerceAtLeast(1) }
    val contentHeight = with(density) { (height - if (compact) 0.dp else 6.dp).roundToPx().coerceAtLeast(0) }
    fun measuredHeight(text: String, style: androidx.compose.ui.text.TextStyle, lines: Int) =
        textMeasurer.measure(text, style, maxLines = lines, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = textWidth)).size.height
    val title = prefix + name
    val location = if (pastel) "@$place" else place
    // Reserve complete measured lines, including font padding. On short cards,
    // reduce optional metadata before it can squeeze another field to half a line.
    val titleHeight = measuredHeight(title, nameStyle, 1)
    var remaining = (contentHeight - titleHeight).coerceAtLeast(0)
    var placeLines = 0
    if (p.showCardPlace && place.isNotBlank() && !compact) {
        val oneLine = measuredHeight(location, subStyle, 1)
        if (oneLine <= remaining) {
            placeLines = 1
            remaining -= oneLine
        }
    }
    val teacherHeight = measuredHeight(teacher, subStyle, 1)
    val showTeacher = p.showCardTeacher && teacher.isNotBlank() && !compact && teacherHeight <= remaining
    if (showTeacher) remaining -= teacherHeight
    if (placeLines == 1 && height >= 60.dp) {
        val extra = measuredHeight(location, subStyle, 2) - measuredHeight(location, subStyle, 1)
        if (extra <= remaining) {
            placeLines = 2
            remaining -= extra
        }
    }
    val nameLines = if (compact) 1 else (1..5).last { lines ->
        measuredHeight(title, nameStyle, lines) <= titleHeight + remaining
    }
    val textColor = when {
        inactive -> colors.inactiveText
        pastel -> {
            val cardSurface = color.copy(alpha = PastelFillAlpha).compositeOver(colors.background)
            Color(
                CoursePalette.pastelTextArgb(
                    color.toArgb().toLong() and 0xFFFFFFFFL,
                    cardSurface.toArgb().toLong() and 0xFFFFFFFFL,
                    colors.dark,
                )
            )
        }
        p.cardTextColorMode == "light" -> Color.White
        p.cardTextColorMode == "dark" -> Color(0xFF142036)
        else -> courseTextColor(color)
    }
    val corner = if (compact) 3.dp else p.cardCornerDp.coerceIn(0f, 28f).dp
    val shape = ContinuousRoundedRectangle(corner)
    val radiusPx = with(density) { corner.toPx() }
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }
    val useStaticGlass =
        !pastel && !inactive && !compact && glassSurface != null &&
            materialLevel != GlassMaterialLevel.NONE
    val fillAlpha = when {
        inactive -> if (pastel) 0.14f else 0.85f
        pastel -> PastelFillAlpha
        materialLevel == GlassMaterialLevel.NONE -> 1f
        else -> p.cardOpacity.coerceIn(0.25f, 1f)
    }
    val glassModifier = if (useStaticGlass) {
        Modifier.onGloballyPositioned { cardOrigin = it.positionInRoot() }
            .then(
                glassSurface!!.modifier(
                    density = density,
                    origin = { cardOrigin },
                    radiusPx = radiusPx,
                    redraw = redrawSignal,
                )
            )
            .background(color.copy(alpha = (p.cardTintStrength * fillAlpha).coerceIn(0.15f, 0.92f)), shape)
    } else {
        Modifier.background(color.copy(alpha = fillAlpha), shape)
    }
    val verticalArrangement = when (p.cardAlignment) {
        "top" -> Arrangement.Top
        "bottom" -> Arrangement.Bottom
        else -> Arrangement.Center
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.width(width).height(height).coursePressFeedback(interaction)
            .clip(shape).then(glassModifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = if (compact) 0.dp else 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = verticalArrangement,
        ) {
            Text(title, color = textColor, style = nameStyle, maxLines = nameLines, overflow = TextOverflow.Ellipsis)
            if (placeLines > 0) Text(location, color = textColor.copy(alpha = if (pastel) 1f else 0.88f), style = subStyle, maxLines = placeLines, overflow = TextOverflow.Ellipsis)
            if (showTeacher) Text(teacher, color = textColor.copy(alpha = if (pastel) 0.9f else 0.8f), style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hasWeekNote && !compact) {
            Text(
                "注",
                color = textColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.16f), ContinuousRoundedRectangle(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}