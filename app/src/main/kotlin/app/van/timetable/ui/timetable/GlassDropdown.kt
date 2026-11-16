// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.ui.LocalVan
import app.van.timetable.ui.nexio.*
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.shape.ContinuousRoundedRectangle

/**
 * 玻璃下拉面板：课表切换与课表菜单共用。
 * 自带滑动手势（[DropdownPanelDragSelectState]）与出入缩放，调用点只排内容。
 */
@Composable
internal fun GlassDropdownPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    backdrop: GlassBackdrop?,
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
internal fun GlassDropdownItem(
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