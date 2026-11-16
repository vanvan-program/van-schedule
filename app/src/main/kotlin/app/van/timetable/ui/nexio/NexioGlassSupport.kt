// SPDX-License-Identifier: GPL-3.0-only
// Ported from NexioSchedule (AGPL-3.0) SoftGlassSurface.kt & AppMaterialSettings.kt
package app.van.timetable.ui.nexio

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.Preferences
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import com.haooz.softglass.backdrop.effects.runtimeShaderEffect
import com.haooz.softglass.glass.BrightTone
import com.haooz.softglass.glass.CustomizeFill
import com.haooz.softglass.glass.SOFT_GLASS_LIVE_SHADER
import com.haooz.softglass.glass.SoftGlassBackdrop
import com.haooz.softglass.glass.SoftGlassStyle
import com.haooz.softglass.glass.ThemeFill
import com.haooz.softglass.glass.applySoftGlass
import com.haooz.softglass.glass.glassBlurRadiusForSigma
import com.haooz.softglass.glass.prepareSoftGlassBackdrop
import com.haooz.softglass.glass.softGlass
import com.haooz.softglass.glass.wallpaperTexMap
import com.haooz.softglass.platform.asSoftwareBitmap
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.platform.isRuntimeShaderSupported
import com.haooz.softglass.platform.isSoftGlassShaderAvailable
import com.haooz.softglass.tuning
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface VanSoftGlassSurface {
    fun modifier(
        density: Density,
        origin: () -> Offset,
        radiusPx: Float?,
        redraw: () -> Unit = {},
    ): Modifier
}

val LocalVanSoftGlassSurface = staticCompositionLocalOf<VanSoftGlassSurface?> { null }
val LocalVanGlassRedrawSignal = staticCompositionLocalOf<() -> Unit> { {} }
val LocalVanMaterialLevel = staticCompositionLocalOf { GlassMaterialLevel.SOFT }
val LocalVanBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

fun Preferences.resolvedMaterialLevel(): GlassMaterialLevel {
    if (!isRenderEffectSupported()) return GlassMaterialLevel.NONE
    return when (materialLevel) {
        "none" -> GlassMaterialLevel.NONE
        "frosted" -> GlassMaterialLevel.FROSTED
        else -> GlassMaterialLevel.SOFT
    }
}

fun Preferences.toCardSoftGlassStyle(
    isDark: Boolean,
    tintColor: Color? = null,
): SoftGlassStyle {
    val level = resolvedMaterialLevel()
    val frosted = level == GlassMaterialLevel.FROSTED
    val none = level == GlassMaterialLevel.NONE
    return SoftGlassStyle(
        cornerRadius = cardCornerDp.coerceIn(0f, 28f).dp,
        smallBlurRadius = if (none) 0f else cardBlurDp.coerceIn(0f, 36f),
        refractionScale = when {
            none || frosted -> 0f
            else -> cardRefraction.coerceIn(0f, 1.5f)
        },
        edgeHighlight = !none && !frosted,
        fill = tintColor?.let { CustomizeFill(it) } ?: ThemeFill,
        tone = BrightTone,
        glassColorMix = cardTintStrength.coerceIn(0f, 1f),
        alpha = cardOpacity.coerceIn(0.15f, 1f),
    ).filled(isDark)
}

fun chromeGlassStyle(
    level: GlassMaterialLevel,
    isDark: Boolean,
    tint: Color? = null,
): SoftGlassStyle {
    val base = SoftGlassStyle(
        fill = tint?.let { CustomizeFill(it) } ?: ThemeFill,
        tone = BrightTone,
    ).filled(isDark)
    return if (level == GlassMaterialLevel.SOFT) {
        base
    } else {
        base.copy(refractionScale = 0f, edgeHighlight = false)
    }
}

@Composable
fun Modifier.vanSoftGlassBackdrop(
    backdrop: GlassBackdrop,
    shape: Shape,
    style: SoftGlassStyle,
    radiusPx: Float,
    keySuffix: String,
    redraw: () -> Unit = {},
    blurSigmaPx: Float? = null,
): Modifier {
    val styleState = rememberUpdatedState(style)
    val redrawState = rememberUpdatedState(redraw)
    val blurSigmaState = rememberUpdatedState(blurSigmaPx)
    val shapeProvider: () -> Shape = remember(shape) { { shape } }
    val effects: GlassEffectScope.() -> Unit = remember(radiusPx, keySuffix) {
        {
            redrawState.value()
            val s = styleState.value
            val sigma = blurSigmaState.value
            blur(
                if (sigma != null) glassBlurRadiusForSigma(sigma) else s.smallBlurRadius,
                TileMode.Mirror,
            )
            padding = 0f
            runtimeShaderEffect(
                key = "VanSoftGlassLive_${keySuffix}_${size.width.toInt()}x${size.height.toInt()}",
                shaderString = SOFT_GLASS_LIVE_SHADER,
                uniformShaderName = "content",
            ) {
                applySoftGlass(
                    style = s,
                    width = size.width,
                    height = size.height,
                    radiusPx = radiusPx,
                )
            }
        }
    }
    return drawBackdrop(
        backdrop = backdrop,
        shadow = null,
        shape = shapeProvider,
        effects = effects,
        onDrawSurface = null,
    )
}

@Composable
fun Modifier.vanChromeGlass(
    backdrop: GlassBackdrop?,
    shape: Shape,
    level: GlassMaterialLevel,
    isDark: Boolean,
    fallbackColor: Color,
    tint: Color? = null,
): Modifier {
    if (backdrop == null || level == GlassMaterialLevel.NONE || !isRenderEffectSupported()) {
        return this
    }
    val style = remember(level, isDark, tint) { chromeGlassStyle(level, isDark, tint) }
    val blurDp = level.tuning().blurDp
    val shapeProvider = remember(shape) { { shape } }
    val effects: GlassEffectScope.() -> Unit = remember(blurDp, style, isDark) {
        {
            blur(blurDp.dp.toPx(), TileMode.Mirror)
            if (isRuntimeShaderSupported()) {
                runtimeShaderEffect(
                    key = "VanChromeGlass_${isDark}_${size.width.toInt()}x${size.height.toInt()}",
                    shaderString = SOFT_GLASS_LIVE_SHADER,
                    uniformShaderName = "content",
                ) {
                    applySoftGlass(
                        style = style,
                        width = size.width,
                        height = size.height,
                    )
                }
            }
        }
    }
    return drawBackdrop(
        backdrop = backdrop,
        shadow = null,
        shape = shapeProvider,
        effects = effects,
        onDrawSurface = { drawRect(fallbackColor) },
    )
}

private const val WALLPAPER_BRIGHTNESS_SETTLE_MS = 120L
private const val GLASS_SOURCE_MAX_EDGE = 1024

@Composable
fun rememberVanSoftGlassSurface(
    wallpaperBitmap: ImageBitmap?,
    wallpaperScale: Float,
    wallpaperOffset: Offset,
    screenWidthPx: Float,
    screenHeightPx: Float,
    wallpaperBrightness: Float = 0f,
    style: SoftGlassStyle = SoftGlassStyle(),
    originTransform: (Offset) -> Offset = { it },
): VanSoftGlassSurface? {
    val sourceBitmap = wallpaperBitmap
    if (sourceBitmap == null || !isSoftGlassShaderAvailable()) return null

    var settledBrightness by remember(sourceBitmap) { mutableStateOf(wallpaperBrightness) }
    LaunchedEffect(sourceBitmap, wallpaperBrightness) {
        if (settledBrightness == wallpaperBrightness) return@LaunchedEffect
        delay(WALLPAPER_BRIGHTNESS_SETTLE_MS)
        settledBrightness = wallpaperBrightness
    }
    val backdropBitmap = remember(sourceBitmap, settledBrightness) {
        tintWallpaperForGlass(sourceBitmap, settledBrightness)
    }
    val backdrop = remember(backdropBitmap) {
        prepareSoftGlassBackdrop(backdropBitmap)
    } ?: return null

    val styleState = remember { mutableStateOf(style) }
    SideEffect { styleState.value = style }

    val texMap = remember(backdrop, wallpaperScale, wallpaperOffset, screenWidthPx, screenHeightPx) {
        wallpaperTexMap(
            textureWidth = backdrop.levels[0].width,
            textureHeight = backdrop.levels[0].height,
            wallpaperScale = wallpaperScale,
            wallpaperOffset = wallpaperOffset,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
        )
    }
    val texMapState = remember { mutableStateOf(texMap) }
    SideEffect { texMapState.value = texMap }

    return remember(backdrop, originTransform) {
        DefaultVanSoftGlassSurface(backdrop, styleState, texMapState, originTransform)
    }
}

private fun tintWallpaperForGlass(bitmap: ImageBitmap, brightness: Float): ImageBitmap {
    if (brightness == 0f) return bitmap
    val gain = (1f + brightness / 50f).coerceIn(0f, 2f)
    if (gain == 1f) return bitmap

    val source = bitmap.asSoftwareBitmap()
    val longest = max(source.width, source.height)
    val scale = if (longest > GLASS_SOURCE_MAX_EDGE) GLASS_SOURCE_MAX_EDGE.toFloat() / longest else 1f
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)

    val out = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
    Canvas(out).drawImageRect(
        image = source,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(source.width, source.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
        paint = Paint().apply {
            filterQuality = FilterQuality.Medium
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        gain, 0f, 0f, 0f, 0f,
                        0f, gain, 0f, 0f, 0f,
                        0f, 0f, gain, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        },
    )
    return out
}

private class DefaultVanSoftGlassSurface(
    private val backdrop: SoftGlassBackdrop,
    private val styleState: State<SoftGlassStyle>,
    private val texMapState: State<FloatArray>,
    private val originTransform: (Offset) -> Offset,
) : VanSoftGlassSurface {
    override fun modifier(
        density: Density,
        origin: () -> Offset,
        radiusPx: Float?,
        redraw: () -> Unit,
    ): Modifier = Modifier.softGlass(
        backdrop = backdrop,
        styleProvider = { styleState.value },
        density = density,
        origin = { originTransform(origin()) },
        texMapProvider = { texMapState.value },
        radiusPx = radiusPx,
        redraw = redraw,
    )
}

object WallpaperStore {
    suspend fun saveFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
        val target = File(dir, "schedule_wallpaper_${System.currentTimeMillis()}.jpg")
        dir.listFiles()?.filter { it.name.startsWith("schedule_wallpaper_") }?.forEach { it.delete() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选图片")
        target.absolutePath
    }

    fun clearFiles(context: Context) {
        val dir = File(context.filesDir, "wallpapers")
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun loadImageBitmap(path: String, maxEdge: Int = 1920): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (longest / (sample * 2) >= maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        }
}
