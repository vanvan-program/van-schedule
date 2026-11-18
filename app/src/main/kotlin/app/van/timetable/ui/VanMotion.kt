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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.ui.navigation.VanRoute
import app.van.timetable.ui.nexio.dropdownPanelEntry
import com.haooz.softglass.shape.ContinuousRoundedRectangle

// Immutable destination identity: outgoing content must not read the new route/tab.
internal data class VanDestination(
    val route: VanRoute,
    val tab: Int,
    val scheduleId: String?,
    val importKey: String,
    val depth: Int,
) {
    val key: String get() = "${route.key}:$tab:${scheduleId.orEmpty()}:$importKey"
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
            "设置" to Icons.Outlined.Settings,
        )
    }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val c = LocalVan.current
    val navMinHeight =
        ((if (config.screenHeightDp < 640) 48f else 54f) * density.fontScale.coerceIn(0.85f, 1.2f))
            .coerceIn(48f, 68f)
            .dp
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = navMinHeight)) {
        val slot = maxWidth / items.size
        val indicatorW = (slot - 12.dp).coerceAtLeast(40.dp)
        val indicatorX = animateDpAsState(
            slot * tab + (slot - indicatorW) / 2,
            spring(dampingRatio = 0.9f, stiffness = 550f),
            label = "tab-indicator",
        )
        // 选中项是一整块实心主题色药丸（此前只是很淡的一层浅色，几乎看不出选中），
        // 药丸罩住图标 + 文字整列，选中/未选中对比明显。
        Box(
            Modifier.offset { IntOffset(indicatorX.value.roundToPx(), 4.dp.roundToPx()) }
                .size(indicatorW, 44.dp)
                .background(c.blue, ContinuousRoundedRectangle(16.dp))
                .testTag("tab-indicator")
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, (label, icon) ->
                val selected = tab == index
                val tint by animateColorAsState(
                    when {
                        selected && c.dark -> c.background
                        selected -> Color.White
                        else -> c.muted
                    },
                    tween(150), label = "tab-tint",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = navMinHeight)
                        .testTag("tab-$index")
                        .dropdownPanelEntry(
                            enabled = true,
                            selected = tab == index,
                            action = { if (tab != index) onSelect(index) },
                        )
                        .selectable(selected = tab == index, role = Role.Tab,
                            onClick = { if (tab != index) onSelect(index) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.padding(horizontal = 15.dp, vertical = 2.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    }
                    Text(
                        label,
                        color = tint,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
