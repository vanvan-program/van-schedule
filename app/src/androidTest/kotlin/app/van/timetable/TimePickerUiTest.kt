// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.van.timetable.domain.Preferences
import app.van.timetable.ui.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TimePickerUiTest {
    @get:Rule val ui = createComposeRule()

    private fun day(value: String) = hasText(
        LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
        substring = true,
    )

    @Test fun clockDraftValidatesAndCancelPreservesExistingValues() {
        var times by mutableStateOf("11:05" to "12:35")
        ui.setContent {
            VanTheme(Preferences(theme = "dark")) {
                Column { ClockRangeField(times.first, times.second, { a, b -> times = a to b }, optional = true) }
            }
        }
        ui.onNodeWithText("11:05 – 12:35").performClick()
        ui.onNodeWithTag("clock-minute").performTouchInput { swipeUp(durationMillis = 370) }
        ui.onNodeWithText("取消").performClick()
        ui.runOnIdle { assertEquals("11:05" to "12:35", times) }
        ui.onNodeWithText("11:05 – 12:35").performClick()
        ui.onNodeWithText("键盘输入").performClick()
        ui.onNode(hasSetTextAction() and hasText("结束时间 HH:mm")).performTextReplacement("10:00")
        ui.onNodeWithText("确定时间").assertIsNotEnabled()
        ui.onNode(hasSetTextAction() and hasText("结束时间 HH:mm")).performTextReplacement("13:47")
        ui.onNodeWithText("滚轮选择").performClick()
        ui.onNodeWithText("结束 13:47").performClick()
        ui.onNodeWithText("确定时间").performClick()
        ui.onNodeWithText("11:05 – 13:47").performClick()
        ui.onNodeWithText("沿用原时间").performClick()
        ui.runOnIdle { assertEquals("" to "", times) }
    }

    @Test fun holidayRangeDraftDoesNotChangeFormUntilConfirmed() {
        var range by mutableStateOf("2026-11-05" to "2026-11-17")
        ui.setContent {
            VanTheme(Preferences(theme = "light")) {
                Column { HolidayDateRangeField(range.first, range.second) { a, b -> range = a to b } }
            }
        }
        ui.onNodeWithTag("holiday-date-range").performClick()
        ui.onNodeWithTag("holiday-range-calendar").assertIsDisplayed()
        ui.onNode(day("2026-11-27"), useUnmergedTree = true).performClick()
        ui.onNodeWithText("取消").performClick()
        ui.runOnIdle { assertEquals("2026-11-05" to "2026-11-17", range) }
        ui.onNodeWithTag("holiday-date-range").performClick()
        ui.onNode(day("2026-11-27"), useUnmergedTree = true).performClick()
        ui.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("holiday-range-calendar")))
            .performScrollToNode(day("2026-12-03"))
        ui.onNode(day("2026-12-03"), useUnmergedTree = true).performClick()
        ui.onNodeWithText("确定范围").performClick()
        ui.runOnIdle { assertEquals("2026-11-27" to "2026-12-03", range) }
        ui.onNodeWithTag("holiday-date-range").performClick()
        ui.onNode(day("2026-11-25"), useUnmergedTree = true).performClick()
        ui.onNodeWithText("仅选这一天").performClick()
        ui.runOnIdle { assertEquals("2026-11-25" to "2026-11-25", range) }
    }

    @Test fun semesterOnlyAcceptsMondaysAndSingleTapReturnsToForm() {
        var date by mutableStateOf("2026-11-02")
        ui.setContent {
            VanTheme(Preferences(theme = "dark")) {
                CalendarDateField("第1周周一", date, { date = it }, onlyMondays = true)
            }
        }
        ui.onNodeWithText("2026-11-02 周一").performClick()
        ui.onNode(day("2026-11-10")).assertIsNotEnabled()
        ui.onNode(day("2026-11-09")).performClick()
        ui.onNodeWithTag("calendar-picker").assertDoesNotExist()
        ui.onNodeWithText("2026-11-09 周一").assertIsDisplayed()
    }
}
