// SPDX-License-Identifier: GPL-3.0-only
// Ported from NexioSchedule (AGPL-3.0) TextMotion.kt
package app.van.timetable.ui.nexio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.haooz.softglass.platform.isRenderEffectSupported
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TEXT_MOTION_MILLIS = 320
private const val TEXT_MOTION_BLUR_ATTACK_MILLIS = 140
private const val TEXT_MOTION_BLUR_REATTACK_MIN_MILLIS = 40
private const val TEXT_MOTION_BLUR_RELEASE_MILLIS = 180
private val TEXT_MOTION_BLUR = 8.dp

val LocalTextMotionInstant = staticCompositionLocalOf { false }
val LocalTextMotionEnabled = staticCompositionLocalOf { true }

private class TextMotionAnims {
    val progress by lazy { Animatable(1f) }
    val blurLevel by lazy { Animatable(0f) }
}

@Composable
fun <T : Any> TextMotionBox(
    key: T,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    content: @Composable (text: T, modifier: Modifier) -> Unit,
) {
    if (LocalTextMotionInstant.current || !LocalTextMotionEnabled.current) {
        content(key, modifier)
        return
    }

    var displayed by remember { mutableStateOf(key) }
    var outgoing by remember { mutableStateOf<T?>(null) }
    var blurActive by remember { mutableStateOf(false) }
    val anims = remember { TextMotionAnims() }
    val supported = remember { isRenderEffectSupported() }
    val latestKey by rememberUpdatedState(key)

    LaunchedEffect(Unit) {
        var isFirstEmission = true
        snapshotFlow { latestKey }.collectLatest { newKey ->
            if (isFirstEmission) {
                isFirstEmission = false
                displayed = newKey
                outgoing = null
                return@collectLatest
            }
            if (newKey == displayed) return@collectLatest
            if (!supported || !TextMotionBudget.tryAcquire()) {
                displayed = newKey
                outgoing = null
                blurActive = false
                return@collectLatest
            }
            try {
                val progress = anims.progress
                val blurLevel = anims.blurLevel
                if (progress.value >= 1f) progress.snapTo(0f)
                outgoing = displayed
                displayed = newKey
                blurActive = true
                launch {
                    val remaining =
                        ((1f - progress.value) * TEXT_MOTION_MILLIS).roundToInt().coerceAtLeast(1)
                    progress.animateTo(1f, tween(remaining, easing = FastOutSlowInEasing))
                    outgoing = null
                }
                val from = blurLevel.value
                if (from < 1f) {
                    val attack = ((1f - from) * TEXT_MOTION_BLUR_ATTACK_MILLIS)
                        .roundToInt()
                        .coerceIn(TEXT_MOTION_BLUR_REATTACK_MIN_MILLIS, TEXT_MOTION_BLUR_ATTACK_MILLIS)
                    blurLevel.animateTo(
                        1f,
                        tween(attack, easing = if (from > 0f) LinearOutSlowInEasing else FastOutSlowInEasing),
                    )
                }
                blurLevel.animateTo(
                    0f,
                    tween(TEXT_MOTION_BLUR_RELEASE_MILLIS, easing = FastOutSlowInEasing),
                )
                blurActive = false
            } finally {
                TextMotionBudget.release()
            }
        }
    }

    if (outgoing == null && !blurActive) {
        content(displayed, modifier)
        return
    }

    val out = outgoing
    val progress = anims.progress
    val blurLevel = anims.blurLevel
    Layout(
        modifier = modifier.then(
            Modifier.graphicsLayer {
                val blurPx = TEXT_MOTION_BLUR.toPx() * blurLevel.value
                renderEffect = if (supported && blurPx > 0.01f) {
                    BlurEffect(blurPx, blurPx, TileMode.Decal)
                } else null
            }
        ),
        content = {
            if (out != null) {
                content(out, Modifier.graphicsLayer { alpha = 1f - progress.value })
                content(displayed, Modifier.graphicsLayer { alpha = progress.value })
            } else {
                content(displayed, Modifier)
            }
        },
    ) { measurables, constraints ->
        val outPlaceable = if (out != null) measurables[0].measure(constraints) else null
        val inPlaceable = measurables[measurables.size - 1].measure(constraints)

        val width = maxOf(outPlaceable?.width ?: 0, inPlaceable.width)
        val height = maxOf(outPlaceable?.height ?: 0, inPlaceable.height)

        layout(width, height) {
            outPlaceable?.place(alignX(outPlaceable.width, width, textAlign, layoutDirection), 0)
            inPlaceable.place(alignX(inPlaceable.width, width, textAlign, layoutDirection), 0)
        }
    }
}

@Composable
fun MotionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    TextMotionBox(key = text, modifier = modifier, textAlign = textAlign) { value, m ->
        Text(
            text = value,
            modifier = m,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            style = style,
        )
    }
}

private fun alignX(
    textWidth: Int,
    boxWidth: Int,
    textAlign: TextAlign?,
    layoutDirection: LayoutDirection,
): Int {
    val slack = boxWidth - textWidth
    if (slack <= 0) return 0
    val ltr = layoutDirection == LayoutDirection.Ltr
    return when (textAlign) {
        TextAlign.Center -> slack / 2
        TextAlign.End -> if (ltr) slack else 0
        TextAlign.Start -> if (ltr) 0 else slack
        TextAlign.Right -> slack
        else -> 0
    }
}

internal object TextMotionBudget {
    private const val MAX_CONCURRENT = 48
    private var active = 0

    fun tryAcquire(): Boolean {
        if (active >= MAX_CONCURRENT) return false
        active += 1
        return true
    }

    fun release() {
        if (active > 0) active -= 1
    }
}
