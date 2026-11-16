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
import androidx.compose.ui.graphics.graphicsLayer
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
    val compactAxis = canCollapseGaps && p.showCourseGaps != true
    val textMeasurer = rememberTextMeasurer()
    val numberStyle = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 13.sp)
    val timeStyle = LocalTextStyle.current.copy(fontSize = 9.sp, lineHeight = 11.sp)
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
        val gutter = maxOf((34f * scale).dp, labelWidth + 6.dp)
        val width = maxOf(maxWidth.value, 38f * scale * days + gutter.value).dp
        val dayWidth = (width - gutter) / days
        val cardInsetX = 2.dp
        val cardInsetY = if (compactAxis) 2.dp else 1.dp
        val headerHeight = maxOf(44f, 36f * scale).dp
        val gapHeight = if (compactAxis) 0.dp else (20f * scale).dp
        val gapCount = remember(sections) { GridTimeline.create(sections).bands.count { it.section == null } }
        val fitted = (maxHeight - headerHeight - 8.dp - gapHeight * gapCount) / sections.size
        val minimumRowHeight = maxOf((36f * scale).dp, labelHeight + 4.dp)
        val preferredRowHeight = maxOf(p.rowHeight.dp, minimumRowHeight)
        val rowHeight = if (p.fitWeek) minOf(preferredRowHeight, maxOf(minimumRowHeight, fitted)) else preferredRowHeight
        val timeline = remember(sections, gapHeight, rowHeight) { GridTimeline.create(sections, gapHeight / rowHeight) }
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
        val placed = remember(data, timeline, rowHeight, gapHeight) {
            data.map { day ->
                val gaps = day.entries.groupBy { timeline.gapContaining(it.occurrence.start, it.occurrence.end) }
                day.entries.map { entry ->
                val o = entry.occurrence
                val gap = timeline.gapContaining(o.start, o.end)
                val top = if (gap != null) rowHeight * timeline.position(gap.start) else rowHeight * timeline.position(o.start)
                val height = if (gap != null) gapHeight else rowHeight * (timeline.position(o.end) - timeline.position(o.start))
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
                Text("${monday.monthValue}月", Modifier.width(gutter).padding(top = 12.dp), fontSize = 11.sp, lineHeight = 14.sp)
                repeat(days) { d ->
                    val date = data[d].date
                    Column(Modifier.width(dayWidth).testTag("week-day-$week-$d").clickable { onDaySelected(date) }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(weekdays[d].removePrefix("周"), fontSize = 12.sp, lineHeight = 15.sp, color = if (date == dateNow) colors.blue else colors.text)
                        Text("${date.dayOfMonth}", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = if (date == dateNow) colors.blue else colors.text)
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
                                .width(gutter - 3.dp)
                                .height(rowHeight)
                                .then(
                                    if (isCurrentSection) {
                                        Modifier.background(
                                            colors.blue.copy(alpha = 0.14f),
                                            ContinuousRoundedRectangle(8.dp),
                                        )
                                    } else Modifier
                                )
                                .testTag("section-${n.number}"),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                n.label.ifBlank { "${n.number}" },
                                style = numberStyle,
                                fontSize = if (n.label.isNotBlank()) 10.sp else 11.sp,
                                fontWeight = if (isCurrentSection) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                color = secColor,
                            )
                            Text(n.start, style = timeStyle, maxLines = 1, softWrap = false, color = secColor)
                            Text(n.end, style = timeStyle, maxLines = 1, softWrap = false, color = secColor)
                        }
                    }
                    if (p.showCustomTimeLabels) gapLabels.forEach { (band, times) ->
                        Column(Modifier.offset(y = rowHeight * timeline.position(band.start)).width(gutter - 3.dp).height(gapHeight).testTag("clock-band-${times.first}-${times.second}"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
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
                                Modifier.offset(x = targetX, y = targetY)
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
                        val cardHeight = maxOf(if (compactAxis) 24.dp else 1.dp, item.height - cardInsetY * 2)
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
                            compact = item.compact && !compactAxis,
                            inactive = entry.inactive,
                            hasWeekNote = !o.course.weekNotes[week].isNullOrBlank(),
                            p = p,
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
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalVan.current
    val glassSurface = LocalVanSoftGlassSurface.current
    val redrawSignal = LocalVanGlassRedrawSignal.current
    val materialLevel = LocalVanMaterialLevel.current
    val density = LocalDensity.current
    val nameSize = (if (compact) 9f else if (width < 52.dp) 11f else 12f) * p.cardTextScale.coerceIn(0.75f, 1.4f)
    val subSize = 9f * p.cardTextScale.coerceIn(0.75f, 1.4f)
    val showPlace = p.showCardPlace && place.isNotBlank() && !compact
    val showTeacher = p.showCardTeacher && teacher.isNotBlank() && !compact
    val placeLines = if (height < 60.dp) 1 else 2
    val subCount = (if (showPlace) placeLines else 0) + (if (showTeacher) 1 else 0)
    val subHeight = with(density) { (subSize.sp * subCount * 1.2f).toDp() }
    val nameLines = if (compact) 1 else ((height - subHeight - 6.dp) / with(density) { (nameSize.sp * 1.15f).toDp() }).toInt().coerceIn(1, 5)
    val textColor = when {
        inactive -> colors.inactiveText
        p.cardTextColorMode == "light" -> Color.White
        p.cardTextColorMode == "dark" -> Color(0xFF142036)
        else -> courseTextColor(color)
    }
    val corner = if (compact) 3.dp else p.cardCornerDp.coerceIn(0f, 28f).dp
    val shape = ContinuousRoundedRectangle(corner)
    val radiusPx = with(density) { corner.toPx() }
    var cardOrigin by remember { mutableStateOf(Offset.Zero) }
    val useStaticGlass = !inactive && !compact && glassSurface != null && materialLevel != GlassMaterialLevel.NONE
    val fillAlpha = when {
        inactive -> 0.85f
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
            Text(prefix + name, color = textColor, fontSize = nameSize.sp, lineHeight = (nameSize * 1.15f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = nameLines, overflow = TextOverflow.Ellipsis)
            if (showPlace) Text(place, color = textColor.copy(alpha = 0.88f), fontSize = subSize.sp, lineHeight = (subSize * 1.2f).sp, textAlign = TextAlign.Center, maxLines = placeLines, overflow = TextOverflow.Ellipsis)
            if (showTeacher) Text(teacher, color = textColor.copy(alpha = 0.8f), fontSize = subSize.sp, lineHeight = (subSize * 1.2f).sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
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