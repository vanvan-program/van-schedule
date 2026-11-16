// SPDX-License-Identifier: GPL-3.0-only
// Ported from NexioSchedule (AGPL-3.0) ProgressiveBlurTopBar.kt & ProgressiveBlurShaders.kt
package app.van.timetable.ui.nexio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import com.haooz.softglass.backdrop.effects.runtimeShaderEffect
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.platform.isRuntimeShaderSupported
import java.util.concurrent.atomic.AtomicInteger

private const val MIRROR_GAIN = 2.5f
private const val BLUR_TAPS = 32
private const val DENOISE_TAPS = 12
private const val DENOISE_RADIUS_RATIO = 0.3f
private const val GAUSS_FALLOFF = 0.85f

private const val SOFTER_STEP = """
float softerstep(float a, float b, float x) {
    float s = clamp((x - a) / max(b - a, 0.0001), 0.0, 1.0);
    return s * s * s * (s * (s * 6.0 - 15.0) + 10.0);
}
"""

internal const val PROGRESSIVE_BLUR_SHADER = """
uniform shader content;
uniform float2 contentOrigin;
uniform float2 contentSize;
uniform float maxRadius;
uniform float edgeFadeStart;
layout(color) uniform half4 tint;
uniform float tintIntensity;

const float MIRROR_GAIN = $MIRROR_GAIN;

$SOFTER_STEP

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 progressiveBlur(float2 coord, float radius) {
    if (radius < 0.5) {
        return content.eval(coord);
    }
    float h = hash12(coord);
    float2 dir = float2(cos(h * 6.2831853), sin(h * 6.2831853));
    float2 g = float2(cos(2.39996323), sin(2.39996323));
    half4 sum = half4(0.0);
    float wsum = 0.0;
    float2 span = max(contentSize - 1.0, float2(1.0));
    for (int i = 0; i < $BLUR_TAPS; i++) {
        float fi = float(i);
        float ff = (fi + 0.5) / $BLUR_TAPS.0;
        float r = radius * sqrt(ff);
        r *= 0.90 + 0.20 * fract(h * 93.9898 + fi * 0.7548776662);
        float2 o = dir * r;
        float w = exp(-ff / $GAUSS_FALLOFF);
        float2 relRaw = coord + o - contentOrigin;
        float2 rel = abs(relRaw);
        rel += max(-relRaw, float2(0.0)) * (MIRROR_GAIN - 1.0);
        rel = clamp(rel, float2(0.0), span);
        half4 c = content.eval(contentOrigin + rel);
        if (c.a > 0.02) {
            sum += c * w;
            wsum += w;
        }
        dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
    }
    if (wsum < 0.0001) {
        return content.eval(coord);
    }
    return sum / wsum;
}

half4 main(float2 coord) {
    float2 local = coord - contentOrigin;
    if (local.x < 0.0 || local.y < 0.0 ||
        local.x >= contentSize.x || local.y >= contentSize.y) {
        return content.eval(coord);
    }
    float t = clamp(local.y / max(contentSize.y, 1.0), 0.0, 1.0);
    float u = 1.0 - smoothstep(0.0, 1.0, t);
    half4 color = progressiveBlur(coord, maxRadius * u);
    float edge = softerstep(edgeFadeStart, 1.0, t);
    color *= (1.0 - edge);
    if (tintIntensity > 0.0) {
        color = mix(color, tint * (1.0 - edge), tintIntensity * u);
    }
    return color;
}
"""

internal const val PROGRESSIVE_DENOISE_SHADER = """
uniform shader content;
uniform float2 contentOrigin;
uniform float2 contentSize;
uniform float maxRadius;

const float MIRROR_GAIN = $MIRROR_GAIN;

half4 main(float2 coord) {
    float2 local = coord - contentOrigin;
    if (local.x < 0.0 || local.y < 0.0 ||
        local.x >= contentSize.x || local.y >= contentSize.y) {
        return content.eval(coord);
    }
    float t = clamp(local.y / max(contentSize.y, 1.0), 0.0, 1.0);
    float r = maxRadius * (1.0 - smoothstep(0.0, 1.0, t)) * $DENOISE_RADIUS_RATIO;
    if (r < 0.4) {
        return content.eval(coord);
    }
    half4 sum = content.eval(coord) * half4(2.0);
    float wsum = 2.0;
    float2 dir = float2(1.0, 0.0);
    float2 g = float2(cos(2.39996323), sin(2.39996323));
    float2 span = max(contentSize - 1.0, float2(1.0));
    for (int i = 0; i < $DENOISE_TAPS; i++) {
        float2 relRaw = coord + dir * r - contentOrigin;
        float2 rel = abs(relRaw);
        rel += max(-relRaw, float2(0.0)) * (MIRROR_GAIN - 1.0);
        rel = clamp(rel, float2(0.0), span);
        half4 c = content.eval(contentOrigin + rel);
        if (c.a > 0.02) {
            sum += c;
            wsum += 1.0;
        }
        dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
    }
    return sum / wsum;
}
"""

internal const val PROGRESSIVE_FADE_SHADER = """
uniform shader content;
uniform float2 contentSize;
uniform float fadeStart;
uniform float hold;
uniform float midAlpha;

half4 main(float2 coord) {
    half4 color = content.eval(coord);
    float t = clamp(coord.y / max(contentSize.y, 1.0), 0.0, 1.0);
    float holdEnd = fadeStart * hold;
    float head = mix(1.0, midAlpha,
        clamp((t - holdEnd) / max(fadeStart - holdEnd, 0.0001), 0.0, 1.0));
    float tail = 1.0 - clamp((t - fadeStart) / max(1.0 - fadeStart, 0.0001), 0.0, 1.0);
    return color * (head * tail);
}
"""

private val progressiveBlurShaderSeq = AtomicInteger(0)
private val BlurMaxRadius = 12.dp
private val FullMaskAlpha: () -> Float = { 1f }

@Composable
fun ProgressiveBlurTopBar(
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    height: Dp = Dp.Unspecified,
    tintIntensity: Float = 0.2f,
    tintColor: Color,
    blurAlpha: Float = 1f,
    useFakeProgressiveBlur: Boolean = false,
    useGradientMask: Boolean = false,
    maskAlpha: () -> Float = FullMaskAlpha,
    edgeFadeStart: Float = 0.88f,
    content: @Composable BoxScope.() -> Unit,
) {
    val totalHeight = if (height != Dp.Unspecified) {
        height
    } else {
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        if (statusBarHeight > 0.dp) 58.dp + statusBarHeight else 96.dp
    }

    val blurShapeBlock: () -> Shape = remember { { RectangleShape } }
    val shaderKey = remember { "VanProgressiveBlur_${progressiveBlurShaderSeq.incrementAndGet()}" }
    val denoiseKey = remember(shaderKey) { "${shaderKey}_denoise" }
    val fadeShaderKey = remember { "VanProgressiveFade_${progressiveBlurShaderSeq.incrementAndGet()}" }

    val blurEffects: GlassEffectScope.() -> Unit =
        remember(shaderKey, denoiseKey, tintColor, tintIntensity, edgeFadeStart) {
            {
                val maxRadiusPx = BlurMaxRadius.toPx()
                padding = 0f
                val contentW = size.width * downsampleScale
                val contentH = size.height * downsampleScale

                runtimeShaderEffect(shaderKey, PROGRESSIVE_BLUR_SHADER, "content") {
                    setFloatUniform("contentOrigin", 0f, 0f)
                    setFloatUniform("contentSize", contentW, contentH)
                    setFloatUniform("maxRadius", maxRadiusPx * downsampleScale)
                    setFloatUniform("edgeFadeStart", edgeFadeStart)
                    setColorUniform("tint", tintColor)
                    setFloatUniform("tintIntensity", tintIntensity)
                }
                runtimeShaderEffect(denoiseKey, PROGRESSIVE_DENOISE_SHADER, "content") {
                    setFloatUniform("contentOrigin", 0f, 0f)
                    setFloatUniform("contentSize", contentW, contentH)
                    setFloatUniform("maxRadius", maxRadiusPx * downsampleScale)
                }
            }
        }

    val fakeBlurEffects: GlassEffectScope.() -> Unit = remember(fadeShaderKey, edgeFadeStart) {
        {
            blur(BlurMaxRadius.toPx(), TileMode.Mirror)
            padding = 0f
            runtimeShaderEffect(fadeShaderKey, PROGRESSIVE_FADE_SHADER, "content") {
                setFloatUniform(
                    "contentSize",
                    size.width * downsampleScale,
                    size.height * downsampleScale,
                )
                setFloatUniform("fadeStart", edgeFadeStart.coerceIn(0.4f, 0.95f))
                setFloatUniform("hold", 0.55f)
                setFloatUniform("midAlpha", 0.45f)
            }
        }
    }

    Box(modifier = modifier) {
        key(useFakeProgressiveBlur, useGradientMask) {
            when {
                useGradientMask -> {
                    ProgressiveBlurGradientMask(totalHeight, tintColor, blurAlpha, maskAlpha)
                }
                isRuntimeShaderSupported() && !useFakeProgressiveBlur -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(totalHeight)
                            .graphicsLayer { alpha = blurAlpha }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shadow = null,
                                shape = blurShapeBlock,
                                effects = blurEffects,
                            )
                    )
                }
                isRenderEffectSupported() -> {
                    if (isRuntimeShaderSupported()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(totalHeight)
                                .graphicsLayer { alpha = blurAlpha }
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shadow = null,
                                    shape = blurShapeBlock,
                                    effects = fakeBlurEffects,
                                )
                        )
                    } else {
                        LegacyFakeProgressiveBlur(
                            backdrop = backdrop,
                            blurShapeBlock = blurShapeBlock,
                            totalHeight = totalHeight,
                            blurAlpha = blurAlpha,
                            edgeFadeStart = edgeFadeStart,
                        )
                    }
                }
                else -> {
                    ProgressiveBlurGradientMask(totalHeight, tintColor, blurAlpha, maskAlpha)
                }
            }
        }
        content()
    }
}

@Composable
private fun LegacyFakeProgressiveBlur(
    backdrop: GlassBackdrop,
    blurShapeBlock: () -> Shape,
    totalHeight: Dp,
    blurAlpha: Float,
    edgeFadeStart: Float,
) {
    val fadeBrush = remember(totalHeight, edgeFadeStart) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White,
                (edgeFadeStart * 0.55f).coerceIn(0.2f, 0.9f) to Color.White,
                edgeFadeStart.coerceIn(0.4f, 0.95f) to Color.White.copy(alpha = 0.45f),
                1.0f to Color.Transparent,
            )
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeight)
            .graphicsLayer {
                alpha = blurAlpha
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
            }
            .drawWithContent {
                drawContent()
                drawRect(fadeBrush, blendMode = BlendMode.DstIn)
            }
            .drawBackdrop(
                backdrop = backdrop,
                shadow = null,
                shape = blurShapeBlock,
                effects = {
                    blur(BlurMaxRadius.toPx(), TileMode.Mirror)
                    padding = 0f
                },
            )
    )
}

@Composable
private fun ProgressiveBlurGradientMask(
    totalHeight: Dp,
    fillColor: Color,
    blurAlpha: Float,
    maskAlpha: () -> Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeight)
            .graphicsLayer { alpha = blurAlpha * maskAlpha() }
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to fillColor.copy(alpha = 0.98f),
                        0.4f to fillColor.copy(alpha = 0.92f),
                        0.7f to fillColor.copy(alpha = 0.6f),
                        1.0f to fillColor.copy(alpha = 0.0f),
                    )
                )
            )
    )
}
