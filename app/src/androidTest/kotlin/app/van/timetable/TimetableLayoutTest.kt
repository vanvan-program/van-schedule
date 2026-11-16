package app.van.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.ui.VanTheme
import app.van.timetable.ui.timetable.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimetableLayoutTest {
    @get:Rule val ui = createComposeRule()
    private val monday = LocalDate.of(2026, 11, 16)
    private val schedule = Schedule(
        id = "layout-regression", name = "布局验证", week1Monday = monday.toString(),
        courses = listOf(Course(id = "long", name = "数字图像处理与分析")),
        sessions = listOf(CourseSession(id = "short", courseId = "long", day = 1,
            startSection = 1, endSection = 1, place = "教学楼A305", teacher = "测试教师")),
    )

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).takeScreenshot(
            File(instrumentation.targetContext.getExternalFilesDir(null), name))
    }

    @Test fun shortCardsKeepCompleteTextLinesAndCollapsedBreaksStayHidden() {
        val prefs = mutableStateOf(Preferences(theme = "light", fitWeek = false,
            rowHeight = 56, showCardTeacher = true, showCardPlace = true))
        ui.setContent {
            VanTheme(prefs.value) {
                Box(Modifier.width(360.dp).height(580.dp)) {
                    WeekGrid(schedule, prefs.value, 1, monday, MutableStateFlow(0), true, true,
                        nav = {}, onDaySelected = {})
                }
            }
        }
        for ((font, cardScale, theme) in listOf(
            Triple(1f, 1f, "light"), Triple(1f, 1.35f, "light"), Triple(1.3f, 1.4f, "dark"))) {
            ui.runOnIdle { prefs.value = prefs.value.copy(fontScale = font, cardTextScale = cardScale, theme = theme) }
            ui.waitForIdle()
            ui.onAllNodesWithText("午休").assertCountEquals(0)
            ui.onAllNodesWithText("晚休").assertCountEquals(0)
            val card = hasTestTag("grid-course-short@$monday")
            ui.onNode(card).assertExists()
            val bounds = ui.onNode(card).fetchSemanticsNode().boundsInRoot
            val texts = ui.onAllNodes(hasAnyAncestor(card) and hasText("", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("A course title must always be rendered", texts.isNotEmpty())
            for (node in texts) {
                val layouts = mutableListOf<TextLayoutResult>()
                ui.onNode(hasAnyAncestor(card) and hasText(node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue("Partial text line at font=$font cardScale=$cardScale: $layout",
                    layout.multiParagraph.height <= layout.size.height + 0.5f)
                assertTrue("Text must stay inside the course card", node.boundsInRoot.bottom <= bounds.bottom + 0.5f)
            }
            screenshot("layout-card-$font-$cardScale-$theme.png")
        }
        ui.runOnIdle { prefs.value = prefs.value.copy(showCourseGaps = true) }
        assertTrue("Expanded gaps must still show lunch breaks", ui.onAllNodesWithText("午休").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun narrowHeaderDoesNotOverlapActionsAtLargeFontSize() {
        val prefs = mutableStateOf(Preferences(theme = "light", fontScale = 1.6f))
        ui.setContent {
            VanTheme(prefs.value) {
                Box(Modifier.width(280.dp)) {
                    TimetableHeader(schedule, 18, listOf(schedule), false, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        ui.waitForIdle()
        val week = ui.onNodeWithTag("week-selector").fetchSemanticsNode().boundsInRoot
        for (label in listOf("导入课表", "添加课程", "课表菜单")) {
            val action = ui.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue("Week selector overlaps $label", week.right <= action.left + 0.5f)
        }
        ui.onNodeWithText("第18周", useUnmergedTree = true).assertIsDisplayed()
        screenshot("layout-header-narrow-large-font.png")
    }
}
