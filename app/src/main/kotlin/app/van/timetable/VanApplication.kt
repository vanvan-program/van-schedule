// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Application
import app.van.timetable.data.Store
import app.van.timetable.system.AppDiagnostics
import app.van.timetable.system.Scheduler
import app.van.timetable.system.UpdateDownloads
import app.van.timetable.widget.WidgetMonetHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VanApplication : Application() {
    val store by lazy { Store(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val updateDownloads by lazy { UpdateDownloads(this, scope) }
    val band by lazy { app.van.timetable.band.BandSync(this) }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        WidgetMonetHelper.installWallpaperObserver(this)
        scope.launch {
            combine(store.schedules, store.preferences) { schedules, preferences ->
                    schedules to preferences
                }
                .distinctUntilChanged()
                .debounce(150)
                .collect {
                    try {
                        Scheduler.reconcile(this@VanApplication)
                        band.onChanged()
                        AppDiagnostics.recovered("auto-sync")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppDiagnostics.report("auto-sync", e, "课程提醒与小组件刷新失败")
                    }
                }
        }
    }
}
