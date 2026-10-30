package app.van.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*

class NavigationMotionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Preferences? = null
    private var schedule: Schedule? = null

    @Before fun seed() {
        val course = Course(name = "动效状态验证课程")
        val s = Schedule(
            name = "交互状态验证",
            week1Monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            maxWeeks = 4,
            courses = listOf(course),
            sessions = listOf(CourseSession(courseId = course.id, day = 3, weeks = setOf(1, 2, 3, 4))),
        )
        schedule = s
        runBlocking {
            previous = store.prefs()
            store.save(s)
            store.updatePreferences { it.copy(activeScheduleId = s.id, theme = "light", showInactive = false, reminders = false) }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(s.name).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("week-selector").performClick()
        ui.onNodeWithText("第2周").performClick()
        ui.waitForIdle()
    }

    @After fun restore() {
        ui.mainClock.autoAdvance = true
        runBlocking {
            schedule?.let { store.delete(it.id) }
            previous?.let { p -> store.updatePreferences { p } }
        }
    }

    private fun screenshot(name: String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test fun interruptedTabTransitionsKeepTheSelectedWeek() {
        ui.mainClock.autoAdvance = false
        ui.onNodeWithText("我的").performClick()
        ui.mainClock.advanceTimeBy(80)
        screenshot("motion-tabs-midway.png")
        ui.onNodeWithText("课程", useUnmergedTree = true).performClick()
        ui.mainClock.advanceTimeBy(80)
        ui.onNodeWithText("课表", useUnmergedTree = true).performClick()
        ui.mainClock.advanceTimeBy(800)
        ui.mainClock.autoAdvance = true
        ui.onNodeWithText("第2周 ▾").assertIsDisplayed()
        ui.onNodeWithTag("tab-indicator").assertIsDisplayed()
        screenshot("motion-tabs-restored.png")
    }

    @Test fun courseExpansionAndRepeatedBackKeepOccurrenceAndTheme() {
        val s = schedule!!
        val occurrence = CourseEngine.timetableDay(s, CourseEngine.dateAt(s, 2, 3)).single()
        for (theme in listOf("light", "dark")) {
            runBlocking { store.updatePreferences { it.copy(theme = theme) } }
            ui.onNodeWithTag("grid-course-${occurrence.id}").performClick()
            ui.onNodeWithText("调整 ${occurrence.date} ${occurrence.start} 的课程").assertExists()
            screenshot("motion-detail-$theme.png")
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithText("第2周 ▾").assertIsDisplayed()
            ui.onNodeWithTag("grid-course-${occurrence.id}").assertIsDisplayed()
            screenshot("motion-grid-$theme.png")
        }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("第2周 ▾").assertIsDisplayed()
    }
}
