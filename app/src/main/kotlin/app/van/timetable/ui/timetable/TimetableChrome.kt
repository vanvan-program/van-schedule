// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import app.van.timetable.ui.LocalVan
import app.van.timetable.ui.courseColor
import app.van.timetable.ui.courseTextColor
import app.van.timetable.ui.weekdays
import app.van.timetable.ui.nexio.LocalVanBackdrop
import app.van.timetable.ui.nexio.LocalVanMaterialLevel
import app.van.timetable.ui.nexio.toCardSoftGlassStyle
import app.van.timetable.ui.nexio.vanChromeGlass
import app.van.timetable.ui.nexio.vanSoftGlassBackdrop
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.shape.ContinuousCapsule
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单行「下一节 / 正在上课」提示条。 */
@Composable
internal fun NextClassBar(s: Schedule, now: Instant, p: Preferences, nav: (String) -> Unit) {
    val colors = LocalVan.current
    val dateNow = now.atZone(ZoneId.of(s.zone)).toLocalDate()
    // Searching future days must not block a drag or repeat on page changes.
    val nextState =
        produceState<Occurrence?>(null, s, now, p.globalDayOverrides) {
            value = withContext(Dispatchers.Default) { CourseEngine.next(s, now, p.globalDayOverrides) }
        }
    val next = nextState.value ?: return
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

/**
 * 本周备注条：假期、调休、单次调课与特殊课程汇成一行跑马灯。
 * 固定占位高度，翻周时不因有无备注而抖动。
 */
@Composable
internal fun WeekNotesBar(s: Schedule, week: Int, monday: LocalDate, p: Preferences) {
    val colors = LocalVan.current
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
}

@Composable
internal fun ReturnToTodayButton(daily: Boolean, modifier: Modifier, onClick: () -> Unit) {
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

/** 拖拽中的落点提示胶囊（跨周时用主题色强调）。 */
@Composable
internal fun BoxScope.DragHintChip(dragging: DraggingCourse?, week: Int) {
    val colors = LocalVan.current
    val backdrop = LocalVanBackdrop.current
    val materialLevel = LocalVanMaterialLevel.current
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
}

/** 跟随手指的课程卡副本：高光、玻璃与边框都按抓取点为中心。 */
@Composable
internal fun DragPreviewCard(dragging: DraggingCourse?, p: Preferences, rootPos: Offset) {
    val d = dragging ?: return
    val colors = LocalVan.current
    val backdrop = LocalVanBackdrop.current
    val materialLevel = LocalVanMaterialLevel.current
    val density = LocalDensity.current
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