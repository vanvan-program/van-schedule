// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.ui.VanTheme
import app.van.timetable.ui.timetable.Timetable
import app.van.timetable.ui.weekdays
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version023UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previousBackup: Backup? = null
    private val fixedNow = Instant.parse("2026-10-13T04:00:00Z")
    private val fixedToday = LocalDate.of(2026, 10, 13)
    private val appearance = Preferences(theme = "light", showInactive = false)

    @After
    fun cleanup() {
        previousBackup?.let { backup -> runBlocking { store.restore(backup) } }
    }

    private fun render(schedule: Schedule, preferences: Preferences = appearance) {
        ui.runOnUiThread {
            ui.activity.setContent {
                VanTheme(preferences) { Timetable(schedule, preferences, fixedNow, {}) }
            }
        }
        ui.waitForIdle()
    }

    private fun chooseWeek(week: Int) {
        ui.onNodeWithTag("week-selector").performClick()
        ui.onNodeWithText("第${week}周").performClick()
        ui.onNodeWithText("第${week}周 ▾").assertExists()
    }

    private fun switchMode(text: String) {
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText(text).performClick()
    }

    private fun weeklySchedule() = Schedule(
        name = "0.2.3 返回与布局验证",
        week1Monday = "2026-10-12",
        maxWeeks = 3,
    )

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(instrumentation)
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test
    fun separateRightEdgeButtonReturnsToCurrentWeekAndToday() {
        render(weeklySchedule())
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
        chooseWeek(3)
        val button = ui.onNodeWithTag("jump-today").assertIsDisplayed()
        button.assert(hasContentDescription("回到本周"))
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Return button should sit along the right edge", buttonBounds.left > viewport.center.x)
        assertTrue("Return button should be beside the grid", buttonBounds.top > viewport.top)
        screenshot("023-return-current-week.png")
        button.performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()

        switchMode("切换为日列表")
        ui.onNodeWithText("$fixedToday ${weekdays[fixedToday.dayOfWeek.value - 1]}").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
        ui.onNodeWithText("后一天").performClick()
        ui.onNodeWithTag("jump-today")
            .assertIsDisplayed()
            .assert(hasContentDescription("回到今日"))
            .performClick()
        ui.onNodeWithText("$fixedToday ${weekdays[fixedToday.dayOfWeek.value - 1]}").assertExists()
        ui.onNodeWithTag("jump-today").assertDoesNotExist()
    }

    @Test
    fun adjustedWeekNotesKeepViewportAndSectionPositionsStable() {
        val schedule = weeklySchedule().copy(
            dayOverrides = listOf(
                DayOverride("2026-10-19", "2026-10-12"),
                DayOverride("2026-10-21", cancelled = true),
                DayOverride("2026-10-26", "2026-10-13"),
            ),
            holidays = listOf(Holiday(name = "多条调课说明布局验证", from = "2026-10-19", through = "2026-10-20")),
        )
        render(schedule)
        val viewport = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot
        val notes = ui.onNodeWithTag("week-notes").fetchSemanticsNode().boundsInRoot
        val firstSection = section(1, 1).fetchSemanticsNode().boundsInRoot
        val lastSection = section(1, 10).fetchSemanticsNode().boundsInRoot
        assertTrue("Empty notes region must reserve space", notes.height > 0f)
        for (week in listOf(2, 3, 1)) {
            chooseWeek(week)
            assertBoundsEqual(viewport, ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(notes, ui.onNodeWithTag("week-notes").fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(firstSection, section(week, 1).fetchSemanticsNode().boundsInRoot)
            assertBoundsEqual(lastSection, section(week, 10).fetchSemanticsNode().boundsInRoot)
            if (week == 2) screenshot("023-notes-fixed-grid.png")
        }
    }

    private fun section(week: Int, number: Int) = ui.onNode(
        hasTestTag("section-$number") and hasAnyAncestor(hasTestTag("week-grid-$week")),
        useUnmergedTree = true,
    )

    private fun assertBoundsEqual(expected: Rect, actual: Rect) {
        assertEquals("Left edge", expected.left, actual.left, 1f)
        assertEquals("Top edge", expected.top, actual.top, 1f)
        assertEquals("Width", expected.width, actual.width, 1f)
        assertEquals("Height", expected.height, actual.height, 1f)
    }
}
