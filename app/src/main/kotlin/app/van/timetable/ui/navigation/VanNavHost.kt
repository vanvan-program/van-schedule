// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.Backup
import app.van.timetable.domain.Schedule
import app.van.timetable.system.DocumentSource
import app.van.timetable.ui.*
import app.van.timetable.ui.timetable.Timetable
import java.time.Instant

/**
 * 分发一次导航要显示的页面。
 *
 * 这个文件承担 0.4.0 里 VanApp 的 `when` 大分支：它只决定「显示哪个屏幕」，
 * 不持有导航栈、不发起 Activity 结果回调、不读写文件——那些仍留在 [VanApp]。
 * 依赖通过 [VanNavHostEnv] 显式传入，屏幕本身保持无副作用。
 */
internal class VanNavHostEnv(
    val destination: VanDestination,
    val state: VanState,
    val vm: VanViewModel,
    val now: Instant,
    val imported: Backup?,
    val warnings: List<String>,
    val document: DocumentSource?,
    val openTimetable: Boolean,
    val initialDate: String?,
    val launchSequence: Int,
    val nav: (String) -> Unit,
    val back: () -> Unit,
    val onSelectSchedule: (String) -> Unit,
    val onUpdateSchedule: (Schedule) -> Unit,
    val onPickFile: () -> Unit,
    val onSchoolEntry: (String) -> Unit,
    val onExportBackup: () -> Unit,
    val onRestoreSnapshot: () -> Unit,
    val onImportApplied: () -> Unit,
)

@Composable
internal fun VanNavHost(env: VanNavHostEnv) {
    val state = env.state
    val vm = env.vm
    val nav = env.nav
    val back = env.back
    val active = state.schedules.firstOrNull { it.id == env.destination.scheduleId }

    when (val route = env.destination.route) {
        VanRoute.Home ->
            when (env.destination.tab) {
                0 ->
                    if (active != null)
                        key(
                            active.week1Monday,
                            active.maxWeeks,
                            if (env.openTimetable) env.launchSequence else -1,
                        ) {
                            Timetable(
                                active,
                                state.preferences,
                                env.now,
                                nav,
                                if (env.openTimetable) env.initialDate else null,
                                schedules = state.schedules,
                                onSelectSchedule = env.onSelectSchedule,
                                onUpdateSchedule = env.onUpdateSchedule,
                            )
                        }
                    else if (state.schedules.isEmpty()) Welcome(vm, nav)
                    else
                        Column(Modifier.padding(24.dp)) {
                            Text("尚未启用课表", style = MaterialTheme.typography.headlineMedium)
                            Action("选择启用课表", "已有 ${state.schedules.size} 份课表") {
                                nav("schedules")
                            }
                        }
                1 ->
                    if (active != null) Courses(active, env.now, nav)
                    else
                        Column(Modifier.padding(24.dp)) {
                            Action("先选择启用课表") { nav("schedules") }
                        }
                else -> Mine(state, nav)
            }
        VanRoute.Notes -> active?.let { CourseNotesScreen(it, vm) } ?: Missing(nav)
        VanRoute.Import -> ImportEntry(env.onPickFile, nav)
        VanRoute.DocumentReview ->
            env.document?.let { DocumentReview(vm, it) { nav("preview") } } ?: Missing(nav)
        VanRoute.ImportPreview -> {
            val imported = env.imported
            if (imported != null)
                ImportPreview(vm, imported, env.warnings, back) { env.onImportApplied() }
            else
                Column(Modifier.padding(24.dp)) {
                    Text("导入草稿已清除")
                    TextButton(onClick = back) { Text("返回") }
                }
        }
        VanRoute.School -> SchoolEntry(state.preferences, vm, env.onSchoolEntry, env.onPickFile)
        VanRoute.ScheduleList -> ScheduleList(state, vm, nav)
        VanRoute.NewSchedule -> ScheduleEditor(null, vm, back)
        is VanRoute.ScheduleSettings ->
            state.schedules.firstOrNull { it.id == route.scheduleId }
                ?.let { ScheduleEditor(it, vm, back) } ?: Missing(nav)
        VanRoute.Semester ->
            active?.let { ScheduleEditor(it, vm, back) } ?: Missing(nav)
        VanRoute.Routines ->
            active?.let { Routines(it) { s -> vm.save(s) } } ?: Missing(nav)
        VanRoute.Days -> active?.let { DayAdjustments(it, vm) } ?: Missing(nav)
        VanRoute.Holidays -> active?.let { HolidayScreen(it, vm) } ?: Missing(nav)
        is VanRoute.CourseDetail ->
            active?.let { s ->
                s.courses
                    .firstOrNull { it.id == route.courseId }
                    ?.let {
                        Detail(
                            s,
                            it,
                            route.date,
                            vm,
                            nav,
                            back,
                            route.sessionId,
                            route.originalDate,
                        )
                    } ?: Missing(nav)
            } ?: Missing(nav)
        is VanRoute.CourseEdit ->
            active?.let { s ->
                CourseEditor(s, s.courses.firstOrNull { it.id == route.courseId }, vm, back)
            } ?: Missing(nav)
        is VanRoute.QuickAdd ->
            active?.let { s ->
                s.courses
                    .firstOrNull { it.id == route.parentCourseId }
                    ?.let { parent ->
                        CourseEditor(
                            s,
                            null,
                            vm,
                            back,
                            parent,
                            if (route.kind == "exam") "期末考试" else "实验课",
                        )
                    } ?: Missing(nav)
            } ?: Missing(nav)
        is VanRoute.Adjust ->
            active?.let { s -> SingleAdjustment(s, route.sessionId, route.date, vm, back) }
                ?: Missing(nav)
        VanRoute.Appearance -> Appearance(state.preferences, vm, state.active)
        VanRoute.Notifications -> NotificationSettings(state.preferences, vm)
        VanRoute.Band -> BandScreen(state)
        VanRoute.Widgets -> WidgetGallery(state, vm)
        VanRoute.Backup ->
            BackupScreen(
                active = active,
                nav = nav,
                onExport = env.onExportBackup,
                onPickFile = env.onPickFile,
                onRestoreSnapshot = env.onRestoreSnapshot,
            )
        is VanRoute.Share ->
            state.schedules
                .firstOrNull { it.id == route.scheduleId }
                ?.let { ShareScreen(it, vm) } ?: Missing(nav)
        VanRoute.About -> About(nav)
        VanRoute.Version -> VersionScreen(nav, state.preferences, vm)
        VanRoute.Changelog -> ChangelogScreen()
        is VanRoute.Unknown -> Missing(nav)
    }
}

@Composable
private fun Missing(nav: (String) -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("请先启用一份课表，或该数据已经删除。")
        TextButton(onClick = { nav("schedules") }) { Text("打开课表管理") }
    }
}