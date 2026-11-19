// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportUpdateFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun previewsThenConfirmsLocalAdjustmentAndOverlapAndBacksUpBeforeUpdating() {
        val context = ui.activity
        val store = (context.application as VanApplication).store
        val beforePrefs = runBlocking { store.prefs() }
        val snapshot = File(context.filesDir, "before-restore.van.json")
        val previousSnapshot = snapshot.takeIf { it.exists() }?.readBytes()
        val math = Course(name = "复导数学", note = "带讲义", weekNotes = mapOf(1 to "复习"))
        val history = Course(name = "复导历史")
        val monday = "2026-09-07"
        val mathSlot = CourseSession(courseId = math.id, day = 1, weeks = setOf(1, 2))
        val historySlot = CourseSession(courseId = history.id, day = 2, weeks = setOf(1))
        val localAdjustment = SessionOverride(mathSlot.id, monday, targetDate = "2026-09-11")
        val target = Schedule(name = "QA 待更新课表", week1Monday = monday, courses = listOf(math, history),
            sessions = listOf(mathSlot, historySlot), sessionOverrides = listOf(localAdjustment))
        val other = Schedule(name = "QA 启用课表", week1Monday = monday)
        val importedMath = Course(name = math.name)
        val sport = Course(name = "复导体育")
        val incoming = Schedule(name = "QA 重新导入", week1Monday = "2026-09-14", courses = listOf(importedMath, sport),
            sessions = listOf(
                CourseSession(courseId = importedMath.id, day = 4, weeks = setOf(1, 2)),
                CourseSession(courseId = sport.id, day = 1, weeks = setOf(2)),
            ))

        runBlocking {
            store.save(other)
            store.save(target)
            store.updatePreferences { it.copy(activeScheduleId = other.id, theme = "light", fontScale = 1f, globalDayOverrides = emptyList()) }
        }
        val file = File(context.cacheDir, "exports/import-update.van.json")
        file.parentFile!!.mkdirs()
        file.writeText(Codec.backup(Backup(scope = "single", schedules = listOf(incoming))))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(
                    Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                ) else null
        }
        instrumentation.addMonitor(monitor)
        try {
            ui.waitUntil(10000) { ui.onAllNodesWithText(other.name).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("导入课表").performClick()
            ui.onNodeWithText("选择本地课表文件").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("更新已有课表").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("更新已有课表").performClick()
            ui.onNodeWithText("更新 ${other.name}", substring = true).performClick()
            ui.onNodeWithText(target.name).performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("时间变更 · ${math.name}").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("备份并更新课表").assertIsNotEnabled()
            UiDevice.getInstance(instrumentation).takeScreenshot(File(context.getExternalFilesDir(null), "import-update-review.png"))

            val list = ui.onNodeWithTag("import-update-list")
            list.performScrollToNode(hasText("取消 · ${history.name}"))
            list.performScrollToNode(hasText("新增 · ${sport.name}"))
            list.performScrollToNode(hasText("时间变更 · ${math.name}"))
            list.performScrollToNode(hasText("保留原安排"))
            ui.onAllNodesWithText("保留原安排").onFirst().performClick()
            list.performScrollToNode(hasText("新增时间冲突 1 处"))
            ui.onNodeWithText("备份并更新课表").assertIsNotEnabled()
            ui.onNodeWithText("展开冲突详情").performClick()
            list.performScrollToNode(hasText("2026-09-14"))
            list.performScrollToNode(hasText("确认保留这些时间冲突"))
            ui.onNode(isToggleable()).performClick()
            ui.onNodeWithText("备份并更新课表").assertIsEnabled().performClick()
            ui.onNodeWithText("取消").performClick()
            assertEquals(target, runBlocking { store.all().find { it.id == target.id } })
            if (previousSnapshot == null) assertFalse(snapshot.exists())
            else assertArrayEquals(previousSnapshot, snapshot.readBytes())
            ui.onNodeWithText("备份并更新课表").performClick()
            ui.onNodeWithText("确认").performClick()

            ui.waitUntil(10000) { runBlocking { store.all().find { it.id == target.id }?.courses?.any { it.name == sport.name } == true } }
            runBlocking {
                val updated = store.all().single { it.id == target.id }
                assertEquals(target.name, updated.name)
                assertEquals(monday, updated.week1Monday)
                assertEquals(setOf(math.name, sport.name), updated.courses.map { it.name }.toSet())
                assertEquals(math, updated.courses.single { it.id == math.id })
                assertEquals(mathSlot, updated.sessions.single { it.courseId == math.id })
                assertEquals(listOf(localAdjustment), updated.sessionOverrides)
                assertEquals(other.id, store.prefs().activeScheduleId)
                assertEquals(other, store.all().single { it.id == other.id })
                val backup = store.readBackupSnapshot()
                assertEquals(target, backup.schedules.single { it.id == target.id })
                store.restore(backup)
                assertEquals(target, store.all().single { it.id == target.id })
                assertEquals(other.id, store.prefs().activeScheduleId)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            runBlocking {
                store.delete(target.id)
                store.delete(other.id)
                store.updatePreferences { beforePrefs }
            }
            if (previousSnapshot == null) snapshot.delete() else snapshot.writeBytes(previousSnapshot)
            file.delete()
        }
    }

    @Test fun rejectsAStalePreviewBeforeWritingItsSnapshot() = runBlocking {
        val context = ui.activity
        val store = (context.application as VanApplication).store
        val before = Schedule(name = "QA 快照版本", week1Monday = "2026-09-07")
        val newer = before.copy(name = "QA 已修改")
        val snapshot = File(context.filesDir, "before-restore.van.json")
        val previousSnapshot = snapshot.takeIf { it.exists() }?.readBytes()
        store.save(newer)
        try {
            val failure = runCatching { store.updateFromImport(before, before.copy(name = "过期预览"), store.prefs().globalDayOverrides) }
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("重新核对"))
            assertEquals(newer, store.all().single { it.id == before.id })
            if (previousSnapshot == null) assertFalse(snapshot.exists())
            else assertArrayEquals(previousSnapshot, snapshot.readBytes())
        } finally {
            store.delete(before.id)
        }
    }
}
