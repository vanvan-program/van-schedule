// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import app.van.timetable.domain.CoursePalette
import app.van.timetable.domain.Preferences
import app.van.timetable.ui.nexio.LocalTextMotionEnabled
import app.van.timetable.ui.nexio.LocalVanMaterialLevel
import app.van.timetable.ui.nexio.resolvedMaterialLevel

data class VanColors(
    val dark: Boolean,
    val top: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val blue: Color,
    val tones: List<Color>,
    val inactive: Color,
    val inactiveText: Color,
    val monetActive: Boolean = false,
)

private val light =
    VanColors(
        false,
        Color(0xFFE8F2FC),
        Color(0xFFF8F9FE),
        Color.White,
        Color(0xFF18233A),
        Color(0xFF67748A),
        Color(0xFF3874F6),
        CoursePalette.light.map { Color(it) },
        Color(0xFFD4D7DE),
        Color(0xFF55565A),
    )
private val dark =
    VanColors(
        true,
        Color(0xFF1B2E4A),
        Color(0xFF111A2A),
        Color(0xFF1D2A40),
        Color(0xFFEFF3FA),
        Color(0xFFB2BED1),
        Color(0xFF91B5FF),
        CoursePalette.dark.map { Color(it) },
        Color(0xFF344155),
        Color(0xFFC1CBDA),
    )
val LocalVan = staticCompositionLocalOf { light }

fun VanColors.courseColor(course: app.van.timetable.domain.Course): Color {
    if (course.customColor != null) return Color(course.customColor!!)
    if (monetActive && tones.isNotEmpty()) {
        return tones[course.color.coerceIn(tones.indices)]
    }
    return Color(CoursePalette.argb(course, dark))
}

fun courseTextColor(color: Color): Color =
    Color(CoursePalette.textArgb(color.toArgb().toLong() and 0xFFFFFFFF))

private fun blendColor(a: Color, b: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = a.red * (1f - t) + b.red * t,
        green = a.green * (1f - t) + b.green * t,
        blue = a.blue * (1f - t) + b.blue * t,
        alpha = 1f,
    )
}

@Composable
fun VanTheme(p: Preferences, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark =
        when (p.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val useMonet = p.monetColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicScheme =
        if (useMonet) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null

    val base = if (isDark) dark else light
    val colors =
        if (dynamicScheme != null) {
            val baseTones = if (isDark) CoursePalette.dark else CoursePalette.light
            val monetTones =
                baseTones.mapIndexed { idx, argb ->
                    val raw = Color(argb)
                    val accent = when (idx % 3) {
                        0 -> dynamicScheme.primaryContainer
                        1 -> dynamicScheme.secondaryContainer
                        else -> dynamicScheme.tertiaryContainer
                    }
                    blendColor(raw, accent, 0.32f)
                }
            VanColors(
                dark = isDark,
                top = dynamicScheme.surfaceContainerHigh,
                background = dynamicScheme.background,
                surface = dynamicScheme.surfaceContainerLow,
                text = dynamicScheme.onSurface,
                muted = dynamicScheme.onSurfaceVariant,
                blue = dynamicScheme.primary,
                tones = monetTones,
                inactive = dynamicScheme.surfaceVariant,
                inactiveText = dynamicScheme.onSurfaceVariant,
                monetActive = true,
            )
        } else {
            base
        }

    val scheme =
        dynamicScheme
            ?: if (isDark)
                darkColorScheme(
                    primary = colors.blue,
                    background = colors.background,
                    surface = colors.surface,
                    onSurface = colors.text,
                    onBackground = colors.text,
                )
            else
                lightColorScheme(
                    primary = colors.blue,
                    background = colors.background,
                    surface = colors.surface,
                    onSurface = colors.text,
                    onBackground = colors.text,
                )

    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
    CompositionLocalProvider(
        LocalVan provides colors,
        LocalTextMotionEnabled provides p.textMotionBlur,
        LocalVanMaterialLevel provides p.resolvedMaterialLevel(),
        LocalDensity provides Density(density.density, density.fontScale * p.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = colors.background) { content() }
        }
    }
}
