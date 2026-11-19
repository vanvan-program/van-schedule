// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.content.Context
import app.van.timetable.domain.AppRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class UpdateDownloads(private val context: Context, private val scope: CoroutineScope) {
    data class Task(val release: AppRelease, val state: State)

    sealed interface State {
        data class Busy(val stage: UpdateInstaller.Stage? = null, val percent: Int = 0) : State
        data class Finished(val result: UpdateInstaller.Download) : State
    }

    private val current = MutableStateFlow<Task?>(null)
    val task = current.asStateFlow()

    @Synchronized
    fun start(release: AppRelease, useMirror: Boolean, preferredSource: String): Boolean {
        val asset = release.apk ?: return false
        if (current.value?.state is State.Busy) return false
        current.value = Task(release, State.Busy())
        scope.launch {
            var percent = 0
            val result = try {
                UpdateInstaller.download(context, asset, release.version, useMirror, preferredSource,
                    onProgress = { percent = it },
                    onStage = { stage -> current.value = Task(release, State.Busy(stage, percent)) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateInstaller.Download.Failed("下载失败，请稍后重试")
            }
            current.value = Task(release, State.Finished(result))
        }
        return true
    }
}
