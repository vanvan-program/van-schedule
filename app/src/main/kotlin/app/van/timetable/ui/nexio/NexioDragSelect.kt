// SPDX-License-Identifier: GPL-3.0-only
// Ported from NexioSchedule (AGPL-3.0) DropdownPanelDragSelect.kt
package app.van.timetable.ui.nexio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import app.van.timetable.ui.LocalVan
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val DragSelectReadyFraction = 0.98f
private val ScrollTapSlop = 12.dp

enum class DropdownMaskMode { Fade, Sliding }

private val DropdownMaskSpring =
    spring<Float>(dampingRatio = 0.85f, stiffness = 700f, visibilityThreshold = 0.01f)
private const val DropdownMaskFadeInMs = 90
private const val DropdownMaskFadeOutMs = 220

enum class DragSelectAxis { Vertical, Horizontal }

class DropdownPanelEntry internal constructor() {
    internal var start = 0f
    internal var end = 0f
    internal var maskLeft = 0f
    internal var maskTop = 0f
    internal var maskRight = 0f
    internal var maskBottom = 0f
    internal var coordinates: LayoutCoordinates? = null
    internal var action: (() -> Unit)? = null
    internal var enabled = true
}

class DropdownPanelDragSelectState(
    val axis: DragSelectAxis = DragSelectAxis.Vertical,
    val maskMode: DropdownMaskMode = DropdownMaskMode.Sliding,
) {
    private val entries = mutableStateListOf<DropdownPanelEntry>()

    var selected by mutableStateOf<DropdownPanelEntry?>(null)
        internal set

    var maskTarget by mutableStateOf<DropdownPanelEntry?>(null)
        internal set

    var fitsOnScreen by mutableStateOf(true)
        internal set

    var panelTopInRoot by mutableFloatStateOf(0f)
    var panelLeftInRoot by mutableFloatStateOf(0f)

    internal var maskContainer: LayoutCoordinates? = null

    internal fun register(entry: DropdownPanelEntry) {
        if (!entries.contains(entry)) entries.add(entry)
    }

    internal fun unregister(entry: DropdownPanelEntry) {
        entries.remove(entry)
        if (selected === entry) selected = null
    }

    internal fun updateBounds(entry: DropdownPanelEntry, start: Float, end: Float) {
        entry.start = start
        entry.end = end
    }

    internal fun updateItemRect(entry: DropdownPanelEntry) {
        val c = entry.coordinates ?: return
        val container = maskContainer ?: return
        if (!c.isAttached || !container.isAttached) return
        val pos = container.localPositionOf(c, Offset.Zero)
        entry.maskLeft = pos.x
        entry.maskTop = pos.y
        entry.maskRight = pos.x + c.size.width
        entry.maskBottom = pos.y + c.size.height
    }

    internal fun updateMaskContainer(container: LayoutCoordinates) {
        maskContainer = container
        for (entry in entries) updateItemRect(entry)
    }

    internal fun hitTest(position: Float): DropdownPanelEntry? {
        val hit = entries.firstOrNull { it.enabled && position >= it.start && position < it.end }
        selected = hit
        maskTarget = hit ?: nearestEntry(position)
        return hit
    }

    private fun nearestEntry(position: Float): DropdownPanelEntry? {
        var nearest: DropdownPanelEntry? = null
        var best = Float.MAX_VALUE
        for (entry in entries) {
            if (!entry.enabled) continue
            val distance = abs((entry.start + entry.end) * 0.5f - position)
            if (distance < best) {
                best = distance
                nearest = entry
            }
        }
        return nearest
    }

    internal fun clear() {
        selected = null
    }
}

val LocalDropdownPanelDragSelect =
    compositionLocalOf<DropdownPanelDragSelectState?> { null }

fun Modifier.dropdownPanelDragSelect(
    state: DropdownPanelDragSelectState,
    fraction: () -> Float,
    hapticFeedback: HapticFeedback,
): Modifier = pointerInput(state) {
    val scrollSlop = ScrollTapSlop.toPx()
    val axisPosition: (Offset) -> Float =
        if (state.axis == DragSelectAxis.Horizontal) ({ it.x }) else ({ it.y })
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (fraction() < DragSelectReadyFraction) return@awaitEachGesture
        if (!state.fitsOnScreen) {
            state.hitTest(axisPosition(down.position))
            var scrolled = false
            while (true) {
                val change = awaitPointerEvent().changes.fastFirstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (abs(change.position.y - down.position.y) > scrollSlop ||
                    abs(change.position.x - down.position.x) > scrollSlop
                ) {
                    scrolled = true
                    break
                }
            }
            val tapped = state.selected
            state.clear()
            if (!scrolled) tapped?.action?.invoke()
            return@awaitEachGesture
        }
        down.consume()
        var lastHit = state.hitTest(axisPosition(down.position))
        while (true) {
            val change = awaitPointerEvent().changes.fastFirstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val hit = state.hitTest(axisPosition(change.position))
            if (hit != null && hit !== lastHit) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastHit = hit
            change.consume()
        }
        val hit = state.selected
        state.clear()
        hit?.action?.invoke()
    }
}

fun Modifier.dropdownPanelSlidingMask(
    state: DropdownPanelDragSelectState,
    shape: Shape? = null,
): Modifier = composed {
    if (state.maskMode != DropdownMaskMode.Sliding) return@composed Modifier

    val maskStart = remember { Animatable(0f) }
    val maskEnd = remember { Animatable(0f) }
    val maskAlpha = remember { Animatable(0f) }
    val maskCross = remember { floatArrayOf(0f, 0f) }

    LaunchedEffect(state) {
        snapshotFlow { state.maskTarget }.collect { target ->
            if (target == null) return@collect
            val horizontal = state.axis == DragSelectAxis.Horizontal
            val s = if (horizontal) target.maskLeft else target.maskTop
            val e = if (horizontal) target.maskRight else target.maskBottom
            maskCross[0] = if (horizontal) target.maskTop else target.maskLeft
            maskCross[1] = if (horizontal) target.maskBottom else target.maskRight
            if (maskAlpha.value < 0.01f) {
                maskStart.snapTo(s)
                maskEnd.snapTo(e)
            } else {
                launch { maskStart.animateTo(s, DropdownMaskSpring) }
                launch { maskEnd.animateTo(e, DropdownMaskSpring) }
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.selected != null }.collectLatest { visible ->
            maskAlpha.animateTo(
                if (visible) 1f else 0f,
                tween(if (visible) DropdownMaskFadeInMs else DropdownMaskFadeOutMs),
            )
        }
    }

    val appDark = LocalVan.current.dark
    val maskColor = dropdownPanelEntryHighlightColor(appDark)
    val maskShape = remember(shape) { shape ?: ContinuousRoundedRectangle(14.dp) }
    Modifier
        .onGloballyPositioned {
            val bounds = it.boundsInRoot()
            state.panelLeftInRoot = bounds.left
            state.panelTopInRoot = bounds.top
            state.updateMaskContainer(it)
        }
        .drawWithContent {
            drawContent()
            val a = maskAlpha.value
            if (a <= 0f) return@drawWithContent
            val rect = if (state.axis == DragSelectAxis.Horizontal) {
                Rect(maskStart.value, maskCross[0], maskEnd.value, maskCross[1])
            } else {
                Rect(maskCross[0], maskStart.value, maskCross[1], maskEnd.value)
            }
            drawShapedRect(rect, maskShape, maskColor.copy(alpha = maskColor.alpha * a))
        }
}

private fun dropdownPanelEntryHighlightColor(isDark: Boolean): Color =
    if (isDark) Color.White.copy(0.12f) else Color.Black.copy(0.07f)

private fun DrawScope.drawShapedRect(rect: Rect, shape: Shape, color: Color) {
    val w = rect.width
    val h = rect.height
    if (w <= 0f || h <= 0f) return
    val outline = shape.createOutline(Size(w, h), layoutDirection, this)
    withTransform({ translate(rect.left, rect.top) }) {
        when (outline) {
            is Outline.Rectangle -> clipRect(0f, 0f, w, h) { drawRect(color) }
            is Outline.Rounded -> clipPath(Path().apply { addRoundRect(outline.roundRect) }) { drawRect(color) }
            is Outline.Generic -> clipPath(Path().apply { addPath(outline.path) }) { drawRect(color) }
        }
    }
}

@Composable
fun Modifier.dropdownPanelEntry(
    enabled: Boolean = true,
    state: DropdownPanelDragSelectState? = null,
    highlightPadding: PaddingValues = PaddingValues(0.dp),
    highlightShape: Shape = RectangleShape,
    selected: Boolean = false,
    action: () -> Unit,
): Modifier = composed {
    val dragSelect = state ?: LocalDropdownPanelDragSelect.current
    val entry = remember(dragSelect) { DropdownPanelEntry() }
    DisposableEffect(dragSelect, entry) {
        dragSelect?.register(entry)
        onDispose { dragSelect?.unregister(entry) }
    }
    SideEffect {
        entry.action = action
        entry.enabled = enabled
    }
    val registered = this.onGloballyPositioned {
        val dragState = dragSelect ?: return@onGloballyPositioned
        val bounds = it.boundsInRoot()
        if (dragState.axis == DragSelectAxis.Horizontal) {
            val left = bounds.left - dragState.panelLeftInRoot
            dragState.updateBounds(entry, left, left + it.size.width)
        } else {
            val top = bounds.top - dragState.panelTopInRoot
            dragState.updateBounds(entry, top, top + it.size.height)
        }
        entry.coordinates = it
        dragState.updateItemRect(entry)
    }
    if (dragSelect?.maskMode == DropdownMaskMode.Sliding) return@composed registered

    val dragSelected = dragSelect?.selected
    val visible = if (dragSelected != null) dragSelected === entry else selected
    val maskAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(150),
        label = "dropdownPanelEntryAlpha",
    )
    val appDark = LocalVan.current.dark
    val highlightColor = dropdownPanelEntryHighlightColor(appDark)
    registered.drawBehind {
        if (maskAlpha <= 0f) return@drawBehind
        val padLeft = highlightPadding.calculateLeftPadding(layoutDirection).toPx()
        val padTop = highlightPadding.calculateTopPadding().toPx()
        val padRight = highlightPadding.calculateRightPadding(layoutDirection).toPx()
        val padBottom = highlightPadding.calculateBottomPadding().toPx()
        drawShapedRect(
            Rect(
                padLeft,
                padTop,
                (size.width - padRight).coerceAtLeast(padLeft),
                (size.height - padBottom).coerceAtLeast(padTop),
            ),
            highlightShape,
            highlightColor.copy(alpha = highlightColor.alpha * maskAlpha),
        )
    }
}
