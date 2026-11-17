// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.shape.ContinuousCapsule
import kotlin.math.roundToInt

/**
 * 底部周次滑块。
 *
 * 轨道没有刻度文字，抬手前顶栏的「第N周」也不会变，所以拖动期间在拇指上方浮出一枚
 * 「第N周」小标签——这是拖动过程中唯一能确认「我正跳到第几周」的地方。
 * 抬手后标签消失，静止状态仍是原来那条干净的进度条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScrubber(week: Int, maximum: Int, actualWeek: Int, onChange: (Int) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val animated by animateFloatAsState(week.toFloat(), tween(180), label = "week-slider")
    val target = draft?.roundToInt() ?: week
    val colors = LocalVan.current
    val dotColor = if (colors.dark) colors.blue else Color(0xFF204F9C)
    val value = (draft ?: animated).coerceIn(1f, maximum.toFloat())
    val span = (maximum - 1).coerceAtLeast(1).toFloat()
    val fraction = ((value - 1f) / span).coerceIn(0f, 1f)
    // 拇指中心的行程：两侧各让出 8dp 内容内边距 + 6dp 拇指半径。
    val insetPx = with(LocalDensity.current) { 14.dp.toPx() }
    var labelWidthPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                draft?.roundToInt()?.let(onChange)
                draft = null
            },
            valueRange = 1f..maximum.coerceAtLeast(2).toFloat(),
            steps = (maximum - 2).coerceAtLeast(0),
            enabled = maximum > 1,
            thumb = {
                Box(Modifier.size(12.dp).background(dotColor, CircleShape).testTag("week-scrubber-thumb"))
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(4.dp).testTag("week-scrubber-track")) {
                    val f = ((state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                    val y = size.height / 2f
                    drawLine(colors.blue.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                    if (f > 0f) drawLine(colors.blue, Offset(0f, y), Offset(size.width * f, y),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)
                .testTag("week-scrubber").semantics {
                    contentDescription = "快速切换周次"
                    stateDescription = "第${target}周" + if (target == actualWeek) "，本周" else ""
                },
        )
        if (draft != null) {
            // 标签中心对准拇指中心，再夹在可视宽度内，两端不会被切掉。
            val widthPx = constraints.maxWidth.toFloat()
            val thumbX = insetPx + fraction * (widthPx - insetPx * 2f)
            val labelPx = labelWidthPx.toFloat()
            val maxLeft = (widthPx - labelPx).coerceAtLeast(0f)
            val left = (thumbX - labelPx / 2f).coerceIn(0f, maxLeft)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(left.roundToInt(), 0) }
                    .onSizeChanged { labelWidthPx = it.width },
                shape = ContinuousCapsule(),
                color = colors.blue,
                contentColor = Color.White,
                shadowElevation = 2.dp,
            ) {
                Text(
                    "第${target}周",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("week-scrubber-label"),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}