// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Immutable destination identity: outgoing content must not read the new route/tab.
internal data class VanDestination(
    val route: String,
    val tab: Int,
    val scheduleId: String?,
    val importKey: String,
    val depth: Int,
) {
    val key: String get() = "$route:$tab:${scheduleId.orEmpty()}:$importKey"
}

// Keep lookahead/shared-bound scopes off the dense timetable's scroll path.
internal val LocalDetailVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun Modifier.detailEntrance(): Modifier {
    val visibility = LocalDetailVisibility.current ?: return this
    return with(visibility) {
        animateEnterExit(
            enter = scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.94f),
            exit = scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.97f),
        )
    }
}

/** Animation values are read during drawing. Static cards have no transform layer. */
@Composable
internal fun Modifier.coursePressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (!pressed && scale.value == 1f) return@LaunchedEffect
        scale.animateTo(
            if (pressed) 0.97f else 1f,
            spring(dampingRatio = 0.85f, stiffness = 650f),
        )
    }
    val transforming by remember { derivedStateOf { pressed || scale.value != 1f } }
    return if (transforming) graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    } else this
}

@Composable
internal fun VanTabBar(tab: Int, onSelect: (Int) -> Unit) {
    val items = remember {
        listOf(
            "课表" to Icons.Outlined.CalendarMonth,
            "课程" to Icons.Outlined.MenuBook,
            "我的" to Icons.Outlined.PersonOutline,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        val slot = maxWidth / items.size
        val indicatorX = animateDpAsState(
            slot * tab + (slot - 50.dp) / 2,
            spring(dampingRatio = 0.9f, stiffness = 550f),
            label = "tab-indicator",
        )
        Box(
            Modifier.offset { IntOffset(indicatorX.value.roundToPx(), 5.dp.roundToPx()) }
                .size(50.dp, 24.dp)
                .background(Color(0xFF3874F6), RoundedCornerShape(14.dp))
                .testTag("tab-indicator")
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, (label, icon) ->
                val tint by animateColorAsState(
                    if (tab == index) Color.White else Color(0xFFB2BED1),
                    tween(150), label = "tab-tint",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp)
                        .selectable(selected = tab == index, role = Role.Tab,
                            onClick = { if (tab != index) onSelect(index) })
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.padding(horizontal = 15.dp, vertical = 2.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    }
                    Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}
