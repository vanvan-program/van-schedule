// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.widget.NextWidget
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MakeupSyncTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = (context.applicationContext as VanApplication).store
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val course = Course(name = "补课同步验证")
    private val room = "补课教室 A107"

    @Test fun saturdayMakeupAndUndoRefreshDayNextWidgetStatusAndDetail() {
        val saturday = LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        val monday = saturday.minusDays(5)
        val makeup = DayOverride(saturday.toString(), monday.toString())
        val base = schedule(monday, LocalTime.of(10, 10), LocalTime.of(11, 10)).let { schedule ->
            val saturdayRoutine = schedule.routines.first().copy(
                id = newId(), name = "周六作息",
                sections = listOf(Section(1, "13:10", "14:10")),
            )
            schedule.copy(
                sessions = listOf(schedule.sessions.single().copy(mode = TimeMode.SECTIONS, endSection = 1)),
                routines = listOf(schedule.routines.first(), saturdayRoutine),
                routineRules = listOf(RoutineRule(saturdayRoutine.id, saturday.toString(), saturday.toString())),
            )
        }
        withSchedule(base) {
            withNextWidget { widget ->
                val intent = Intent(context, MainActivity::class.java)
                    .putExtra("scheduleId", base.id).putExtra("date", saturday.toString())
                    .putExtra("openTimetable", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                try {
                    ui.waitUntil(10_000) { ui.onAllNodesWithTag("mode-switch").fetchSemanticsNodes().isNotEmpty() }
                    ui.onNodeWithTag("mode-switch").performClick()
                    assertTrue(displayedText("当天无课"))
                    runBlocking { store.updatePreferences { it.copy(globalDayOverrides = listOf(makeup)) } }

                    await("下一节小组件显示周六的时间与地点") {
                        widgetLine(widget, R.id.next_detail).contains("$saturday\n$room · 13:10–14:10")
                    }
                    await("状态通知显示同一节课") {
                        title(1) == "${course.name} · $room · 13:10" &&
                            notification(1)?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)
                                ?.toString() == "下一节 · $saturday"
                    }
                    ui.onNode(hasText(room, substring = true) and hasAnyAncestor(hasTestTag("day-list")))
                        .assertIsDisplayed()
                    ui.onNode(hasText("13:10–14:10", substring = true) and hasAnyAncestor(hasTestTag("day-list")))
                        .assertIsDisplayed()
                    ui.onNodeWithTag("next-class").assertTextContains("13:10", substring = true).performClick()
                    val detail = "调整 $saturday 13:10 的课程"
                    ui.onNodeWithText(detail).performScrollTo().assertIsDisplayed()
                    device.pressBack()
                    notification(1)!!.notification.contentIntent.send()
                    ui.onNodeWithText(detail).performScrollTo().assertIsDisplayed()
                    context.startActivity(intent)
                    ui.waitUntil(5_000) { ui.onAllNodesWithTag("mode-switch").fetchSemanticsNodes().isNotEmpty() }
                    if (ui.onAllNodesWithTag("day-list").fetchSemanticsNodes().isEmpty()) {
                        ui.onNodeWithTag("mode-switch").performClick()
                    }
                    ui.onNodeWithText("$saturday 周六").assertIsDisplayed()
                    runBlocking { store.updatePreferences { it.copy(globalDayOverrides = emptyList()) } }
                    await("撤销后下一节小组件和状态通知同时清空") {
                        widgetLine(widget, R.id.next_name) == "暂无后续课程" && notification(1) == null
                    }
                    ui.onNodeWithTag("next-class").assertDoesNotExist()
                    assertTrue(displayedText("当天无课"))
                } finally {
                    instrumentation.runOnMainSync {
                        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
                        listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED)
                            .flatMap(lifecycle::getActivitiesInStage)
                            .filterIsInstance<MainActivity>()
                            .forEach { it.finish() }
                    }
                }
            }
        }
    }

    @Test fun alarmFiresWithScreenOffAndRemindersFollowEditingAndUndo() {
        assertTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val now = ZonedDateTime.now(zone).withNano(0)
        assumeTrue(now.toLocalTime() < LocalTime.of(23, 55))
        val today = now.toLocalDate()
        val monday = precedingMonday(today)
        val start = now.plusSeconds(80)
        val rule = DayOverride(today.toString(), monday.toString())
        val base = schedule(monday, start.toLocalTime(), now.plusMinutes(4).toLocalTime())
        val withMakeup = base.copy(dayOverrides = base.dayOverrides + rule)
        val expectedAt = start.minusMinutes(1).toInstant().toEpochMilli()
        withSchedule(base) {
            runBlocking { store.save(withMakeup) }
            await("状态通知显示即将开始的补课") { title(1).contains(room) }
            assertNull("距提醒时间尚早", notification(2))
            device.sleep()
            try {
                assertFalse(device.isScreenOn)
                await("熄屏时系统闹钟发出课前提醒", timeout = 55_000) { notification(2) != null }
                assertTrue(notification(2)!!.postTime >= expectedAt)
                assertEquals("${course.name} · $room · ${start.toLocalTime()}开始", title(2))
            } finally {
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
            }

            val changedRoom = "调课教室 B209"
            val moved = withMakeup.copy(sessionOverrides = listOf(
                SessionOverride(base.sessions.single().id, today.toString(), place = changedRoom),
            ))
            runBlocking { store.save(moved) }
            await("已发的提醒和状态通知同步改教室") {
                title(2).contains(changedRoom) && title(1).contains(changedRoom)
            }
            runBlocking { store.save(base) }
            await("撤销补课时撤下旧通知") { notification(2) == null && notification(1) == null }

            val changedStart = now.plusMinutes(3).toLocalTime()
            runBlocking {
                store.save(withMakeup.copy(
                    courses = listOf(course.copy(leadMinutes = 5)),
                    sessions = listOf(base.sessions.single().copy(start = changedStart.toString())),
                ))
            }
            await("补课保存时已进入提醒时段，立即补发一次") { title(2).contains("${changedStart}开始") }
            notifications.cancel(2)
            await("手动关闭提醒") { notification(2) == null }
            runBlocking { store.updatePreferences { it.copy(statusNotification = false) } }
            await("课表重新核对完成") { notification(1) == null }
            assertNull("仍在提醒时段也不会重发已关闭的通知", notification(2))
        }
    }

    // Separate invocations bracket a real adb reboot. The fixture is skipped in ordinary test runs.
    @Test fun restartFixture() {
        val phase = InstrumentationRegistry.getArguments().getString("restartMakeup")
        assumeTrue(phase == "setup" || phase == "cleanup")
        val fixture = File(context.cacheDir, "makeup-restart-fixture.json")
        if (phase == "cleanup") {
            if (!fixture.exists()) return
            val saved = fixture.readText()
            val preferences = Codec.json.decodeFromString(Preferences.serializer(), saved.substringAfter('\n'))
            runBlocking {
                store.delete(saved.substringBefore('\n'))
                store.updatePreferences { preferences }
            }
            fixture.delete()
            return
        }
        check(!fixture.exists()) { "清理上次重启测试课表后再运行" }
        val now = ZonedDateTime.now(zone).withNano(0)
        assumeTrue(now.toLocalTime() < LocalTime.of(23, 49))
        val monday = precedingMonday(now.toLocalDate())
        val base = schedule(monday, now.plusMinutes(8).toLocalTime(), now.plusMinutes(10).toLocalTime())
        val seeded = base.copy(dayOverrides = base.dayOverrides + DayOverride(now.toLocalDate().toString(), monday.toString()))
        val previousPrefs = runBlocking { store.prefs() }
        fixture.writeText(seeded.id + "\n" + Codec.json.encodeToString(Preferences.serializer(), previousPrefs))
        runBlocking {
            store.save(seeded)
            select(seeded)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await("重启前已生成补课状态通知") { title(1).contains(room) }
        }
    }

    private fun schedule(monday: LocalDate, start: LocalTime, end: LocalTime) = Schedule(
        name = "调课同步验证", week1Monday = monday.toString(), maxWeeks = 1, zone = zone.id,
        courses = listOf(course),
        sessions = listOf(CourseSession(courseId = course.id, mode = TimeMode.CLOCK,
            weeks = setOf(1), start = start.toString(), end = end.toString(), place = room)),
        dayOverrides = listOf(DayOverride(monday.toString(), cancelled = true)),
    )

    private fun precedingMonday(day: LocalDate) = day.with(TemporalAdjusters.previous(DayOfWeek.MONDAY))

    private fun withSchedule(base: Schedule, verify: () -> Unit) {
        val before = runBlocking { store.prefs() }
        try {
            runBlocking {
                store.save(base)
                select(base)
            }
            verify()
        } finally {
            runBlocking {
                store.delete(base.id)
                store.updatePreferences { before }
            }
        }
    }

    private suspend fun select(schedule: Schedule) {
        store.updatePreferences {
            it.copy(activeScheduleId = schedule.id, globalDayOverrides = emptyList(), reminders = true,
                leadMinutes = 1, statusNotification = true, autoSkipEmptyWeekend = false)
        }
    }

    private fun withNextWidget(verify: (AppWidgetHostView) -> Unit) {
        val host = AppWidgetHost(context, 6220)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        try {
            lateinit var view: AppWidgetHostView
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                instrumentation.runOnMainSync {
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, NextWidget::class.java)))
                    host.startListening()
                    view = host.createView(context, id, manager.getAppWidgetInfo(id))
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            verify(view)
        } finally {
            instrumentation.runOnMainSync {
                host.stopListening()
                host.deleteAppWidgetId(id)
            }
        }
    }

    private fun widgetLine(view: AppWidgetHostView, id: Int): String {
        var text = ""
        instrumentation.runOnMainSync { text = view.findViewById<TextView>(id)?.text?.toString().orEmpty() }
        return text
    }

    private fun notification(id: Int): StatusBarNotification? = notifications.activeNotifications.firstOrNull { it.id == id }

    private fun title(id: Int) = notification(id)?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    private fun displayedText(value: String): Boolean {
        val nodes = ui.onAllNodesWithText(value)
        return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
    }

    private fun await(message: String, timeout: Long = 10_000, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (ready()) return
            Thread.sleep(100)
        }
        assertTrue(message, ready())
    }
}
