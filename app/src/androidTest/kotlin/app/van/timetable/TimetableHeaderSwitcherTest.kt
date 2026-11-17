// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.5.7：顶栏的「切换课表」不再收在「更多」菜单里。
 *
 * 现在它是贴在「第N周」左边的一枚药丸（显示当前课表名），点开就是课表列表；
 * 只有一份课表时这枚药丸不出现，顶栏与 0.5.6 完全一致。
 */
@RunWith(AndroidJUnit4::class)
class TimetableHeaderSwitcherTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = (ui.activity.application as VanApplication).store

    private var first: Schedule? = null
    private var second: Schedule? = null
    private var preferences: Preferences? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    private fun schedule(name: String) =
        Schedule(
            name = name,
            week1Monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            maxWeeks = 3,
            courses = listOf(Course(name = "$name 课程")),
        )

    /** [count] 份课表，第一份为启用课表。 */
    private fun seed(count: Int) {
        val a = schedule("主课表")
        val b = if (count > 1) schedule("备用课表") else null
        first = a
        second = b
        runBlocking {
            preferences = store.prefs()
            store.save(a)
            b?.let { store.save(it) }
            store.updatePreferences {
                it.copy(activeScheduleId = a.id, theme = "light", fontScale = 1f, reminders = false)
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(a.name).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun cleanup() {
        runBlocking {
            listOfNotNull(first, second).forEach { store.delete(it.id) }
            preferences?.let { p -> store.updatePreferences { p } }
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.getExternalFilesDir(null), name))
    }

    @Test
    fun switcherSitsLeftOfWeekAndOpensScheduleList() {
        seed(2)
        ui.onNodeWithText("第1周").assertExists()
        val chip = ui.onNodeWithTag("schedule-switcher")
        val chipBounds = chip.fetchSemanticsNode().boundsInRoot
        val weekBounds = ui.onNodeWithTag("week-selector").fetchSemanticsNode().boundsInRoot
        assertTrue("切换课表药丸必须贴在周次左边", chipBounds.right <= weekBounds.left + 0.5f)
        assertTrue("切换课表药丸必须与周次同一行", chipBounds.top < weekBounds.bottom && chipBounds.bottom > weekBounds.top)
        assertTrue("药丸要显示当前课表名", chipBounds.width > 0f)
        screenshot("header-switcher-light.png")

        // 点药丸直接出课表列表，选中另一份就切换
        chip.performClick()
        ui.onNodeWithText("管理全部课表…").assertExists()
        ui.onNodeWithText("备用课表").performClick()
        ui.waitUntil(10000) { runBlocking { store.prefs().activeScheduleId } == second!!.id }
        ui.waitForIdle()
        ui.onNodeWithText("备用课表").assertExists()
        screenshot("header-switcher-switched.png")

        // 「更多」菜单里不该再有「切换课表…」
        ui.onNodeWithContentDescription("课表菜单").performClick()
        assertTrue(
            "「更多」菜单里不应残留切换课表",
            ui.onAllNodesWithText("切换课表…").fetchSemanticsNodes().isEmpty(),
        )
        ui.onNodeWithText("课表管理").assertExists()
        screenshot("header-more-menu.png")
    }

    @Test
    fun singleScheduleKeepsHeaderWithoutSwitcher() {
        seed(1)
        ui.onNodeWithText("第1周").assertExists()
        ui.onNodeWithTag("schedule-switcher").assertDoesNotExist()
        val weekBounds = ui.onNodeWithTag("week-selector").fetchSemanticsNode().boundsInRoot
        for (label in listOf("导入课表", "添加课程", "课表菜单")) {
            val action = ui.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue("周次与 $label 重叠", weekBounds.right <= action.left + 0.5f)
        }
        screenshot("header-single-schedule.png")
    }
}
