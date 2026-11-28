// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

// A century on either side of the initial date; avoid giant pager indices whose
// layout estimates lose pixel precision when converted to Float.
internal const val DAY_PAGER_ANCHOR = 36_500
internal const val DAY_PAGER_COUNT = DAY_PAGER_ANCHOR * 2 + 1

private val movingPageCorners = List(15) { RoundedCornerShape(it.dp) }

/** 一周一页的横向分页容器：逐页缩放/淡出，翻页停稳后回馈触感。 */
@Composable
internal fun TimetablePager(
    state: PagerState,
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    content: @Composable (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state) {
        var previous = state.settledPage
        snapshotFlow { state.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != previous)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                previous = page
            }
    }
    HorizontalPager(
        state = state,
        modifier = modifier,
        userScrollEnabled = userScrollEnabled,
        key = { it },
        // Keep the source card attached when its long press pages across an edge.
        // Normal swipes use Pager's directional prefetch.
        beyondViewportPageCount = if (userScrollEnabled) 0 else 1,
        flingBehavior =
            PagerDefaults.flingBehavior(
                state,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                // A deliberate short drag should change weeks even without a fast fling.
                // Keep normal touch slop, finger tracking, and snap-on-release behavior.
                snapPositionalThreshold = 0.18f,
            ),
    ) { page ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val rawOffset = (state.currentPage - page) + state.currentPageOffsetFraction
                val offset = rawOffset.absoluteValue.coerceIn(0f, 1f)
                alpha = 1f - 0.14f * offset
                val scale = 1f - 0.025f * offset
                scaleX = scale
                scaleY = scale
                translationX = rawOffset * 10f
                clip = offset > 0.01f
                if (clip) shape = movingPageCorners[(14 * offset).roundToInt()]
            }
        ) {
            content(page)
        }
    }
}

// An oversized grid owns the entire gesture if it could pan at its start.
// Consume leftover movement/fling so reaching an edge does not also change weeks.
internal fun Modifier.gridPagingGuard(scroll: ScrollState): Modifier = composed {
    // Gesture bookkeeping is not UI state; changing it must not recompose grids.
    val ownership = remember(scroll) { GridGestureOwnership() }
    val connection =
        remember(scroll) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (ownership.ownsGesture) Offset(available.x, 0f) else Offset.Zero

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity =
                    if (ownership.ownsGesture) Velocity(available.x, 0f) else Velocity.Zero
            }
        }
    nestedScroll(connection).pointerInput(scroll) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            ownership.ownsGesture = false
            var decided = false
            do {
                val change =
                    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                        it.id == down.id
                    } ?: break
                val delta = change.position - change.previousPosition
                if (!decided && delta.x != 0f) {
                    ownership.ownsGesture =
                        if (delta.x < 0) scroll.canScrollForward else scroll.canScrollBackward
                    decided = true
                }
            } while (change.pressed)
        }
    }
}

private class GridGestureOwnership(var ownsGesture: Boolean = false)
