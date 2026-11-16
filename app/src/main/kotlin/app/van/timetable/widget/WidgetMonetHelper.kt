// SPDX-License-Identifier: GPL-3.0-only
// Ported from NexioSchedule (AGPL-3.0) WidgetMonetColors.kt
package app.van.timetable.widget

import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import app.van.timetable.VanApplication
import app.van.timetable.system.Scheduler
import kotlinx.coroutines.launch

object WidgetMonetHelper {
    data class Palette(
        val surface: Int,
        val card: Int,
        val activeCard: Int,
        val title: Int,
        val secondary: Int,
        val accent: Int,
        val divider: Int,
    )

    fun palette(context: Context, dark: Boolean, enabled: Boolean): Palette? {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            Palette(
                surface = scheme.surfaceContainer.toArgb(),
                card = scheme.surfaceContainerLow.toArgb(),
                activeCard = scheme.primaryContainer.toArgb(),
                title = scheme.onSurface.toArgb(),
                secondary = scheme.onSurfaceVariant.toArgb(),
                accent = scheme.primary.toArgb(),
                divider = scheme.outlineVariant.toArgb(),
            )
        }.getOrNull()
    }

    fun tintBackground(views: RemoteViews, id: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
        }
    }

    fun clearBackgroundTint(views: RemoteViews, id: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(id, "setBackgroundTintList", null)
        }
    }

    fun installWallpaperObserver(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val app = context.applicationContext as? VanApplication ?: return
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener(
                WallpaperManager.OnColorsChangedListener { _, _ ->
                    app.scope.launch { Scheduler.reconcile(app) }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }
}
