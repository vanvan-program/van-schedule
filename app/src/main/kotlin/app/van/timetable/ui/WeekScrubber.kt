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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Keep the touch target generous while drawing only a thin track and a round dot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScrubber(week: Int, maximum: Int, actualWeek: Int, onChange: (Int) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val animated by animateFloatAsState(week.toFloat(), tween(180), label = "week-slider")
    val target = draft?.roundToInt() ?: week
    val colors = LocalVan.current
    val dotColor = if (colors.dark) colors.blue else Color(0xFF204F9C)
    Slider(
        value = (draft ?: animated).coerceIn(1f, maximum.toFloat()),
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
                val fraction = ((state.value - state.valueRange.start) /
                    (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                val y = size.height / 2f
                drawLine(colors.blue.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = size.height, cap = StrokeCap.Round)
                if (fraction > 0f) drawLine(colors.blue, Offset(0f, y), Offset(size.width * fraction, y),
                    strokeWidth = size.height, cap = StrokeCap.Round)
            }
        },
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)
            .testTag("week-scrubber").semantics {
                contentDescription = "快速切换周次"
                stateDescription = "第${target}周" + if (target == actualWeek) "，本周" else ""
            },
    )
}
