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
    // The home screen and automatic sync consume the same decoded Room/DataStore snapshot.
    val schedulePreferences = flow {
        emitAll(combine(store.schedules, store.preferences) { schedules, preferences ->
            schedules to preferences
        })
    }.distinctUntilChanged().shareIn(scope, SharingStarted.Lazily, replay = 1)
    internal val updateDownloads by lazy { UpdateDownloads(this, scope) }
    val band by lazy { app.van.timetable.band.BandSync(this) }
    private val resumes = MutableStateFlow(0L)

    fun onTimetableResumed() { resumes.update { it + 1 } }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        WidgetMonetHelper.installWallpaperObserver(this)
        scope.launch {
            // Wait for the first stored snapshot, even if onResume arrives first.
            combine(schedulePreferences, resumes) { _, _ -> Unit }.debounce(150)
                .collect {
                    try {
                        band.onChanged()
                        AppDiagnostics.recovered("band-refresh")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppDiagnostics.report("band-refresh", e, "手环同步失败")
                    }
                    try {
                        Scheduler.reconcile(this@VanApplication)
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
