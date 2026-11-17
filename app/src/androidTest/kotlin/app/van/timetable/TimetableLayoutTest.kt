package app.van.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
    private val density get() = ui.density.density

    private val elevenSections =
        listOf(
            Section(1, "08:10", "08:55"),
            Section(2, "09:05", "09:50"),
            Section(3, "10:30", "11:15"),
            Section(4, "11:25", "12:10"),
            Section(5, "14:10", "14:55"),
            Section(6, "15:05", "15:50"),
            Section(7, "16:30", "17:15"),
            Section(8, "17:25", "18:10"),
            Section(9, "19:15", "20:00"),
            Section(10, "20:10", "20:55"),
            Section(11, "21:05", "21:50"),
        )

    private fun gridSchedule(): Schedule {
        val course = Course(id = "long", name = "数字图像处理与分析")
        val second = Course(id = "signal", name = "数字信号处理")
        return Schedule(
            id = "layout-regression",
            name = "布局验证",
            week1Monday = monday.toString(),
            courses = listOf(course, second),
            routines = listOf(TimeRoutine(sections = elevenSections)),
            sessions =
                listOf(
                    CourseSession(id = "long-morning", courseId = course.id, day = 1, startSection = 1, endSection = 2, place = "教学楼A305", teacher = "测试教师"),
                    CourseSession(id = "signal-evening", courseId = second.id, day = 1, startSection = 9, endSection = 10),
                ),
        )
    }

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation)
            .takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), name))
    }

    private fun showGrid(prefs: MutableState<Preferences>, schedule: Schedule = gridSchedule()) {
        ui.setContent {
            VanTheme(prefs.value) {
                Box(Modifier.width(360.dp).height(600.dp)) {
                    WeekGrid(schedule, prefs.value, 1, monday, MutableStateFlow(0), true, true, nav = {}, onDaySelected = {})
                }
            }
        }
        ui.waitForIdle()
        // 等网格与课程卡真正上屏，避免在首帧就断言。
        ui.waitUntil(10000) {
            ui.onAllNodes(hasTestTagMatching { it.startsWith("grid-course-") }).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rowHeightPx(): Float = ui.onNodeWithTag("section-1").fetchSemanticsNode().boundsInRoot.height

    @Test
    fun mealBandsOnlyAppearForRealLunchAndDinner() {
        val prefs = mutableStateOf(Preferences(theme = "light", fitWeek = true, showCourseGaps = true))
        showGrid(prefs)

        ui.onAllNodesWithText("午休").assertCountEquals(1)
        ui.onAllNodesWithText("晚休").assertCountEquals(1)
        // 10 分钟的课间（08:55-09:05、11:15-11:25…）不能画横带
        ui.onNodeWithTag("break-band-08:55").assertDoesNotExist()
        ui.onNodeWithTag("break-band-11:15").assertDoesNotExist()
        ui.onNodeWithTag("break-band-20:00").assertDoesNotExist()
        // 真正的午休与晚休
        ui.onNodeWithTag("break-band-12:10").assertExists()
        ui.onNodeWithTag("break-band-18:10").assertExists()

        val bands =
            ui.onAllNodes(hasTestTagMatching { it.startsWith("break-band-") })
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
        assertEquals("只应剩下两条休息横带", 2, bands.size)
        val cards =
            ui.onAllNodes(hasTestTagMatching { it.startsWith("grid-course-") or it.startsWith("inactive-course-") })
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
        assertTrue("当前课表应当有课程卡参与比对", cards.isNotEmpty())
        for (band in bands) {
            for (card in cards) {
                assertFalse("休息横带压住了课程卡：band=$band card=$card", band.overlaps(card))
            }
        }
        shot("layout-bands-expanded.png")
    }

    @Test
    fun expandedGapHeightComesFromGapLengthNotAFixedBand() {
        val prefs = mutableStateOf(Preferences(theme = "light", fitWeek = true, showCourseGaps = true))
        showGrid(prefs)
        val expandedRow = rowHeightPx()
        val lunch = ui.onNodeWithTag("break-band-12:10").fetchSemanticsNode().boundsInRoot
        val dinner = ui.onNodeWithTag("break-band-18:10").fetchSemanticsNode().boundsInRoot
        assertTrue("两小时午休应当比一小时晚休更高", lunch.height > dinner.height)
        assertTrue("午休横带不应压到一行课程的高度", lunch.height <= expandedRow)
        assertTrue("课程行不应被空档压扁", expandedRow / density >= 38f)

        prefs.value = prefs.value.copy(showCourseGaps = false)
        ui.waitForIdle()
        ui.onAllNodesWithText("午休").assertCountEquals(0)
        val compactRow = rowHeightPx()
        assertTrue("收起空档后课程行应当更高", compactRow > expandedRow)
        shot("layout-bands-compact.png")
    }

    @Test
    fun shortCardsKeepCompleteTextLinesAndCollapsedBreaksStayHidden() {
        val prefs = mutableStateOf(
            Preferences(theme = "light", fitWeek = false, rowHeight = 56, showCardTeacher = true, showCardPlace = true)
        )
        val schedule =
            Schedule(
                id = "card-regression",
                name = "卡片验证",
                week1Monday = monday.toString(),
                courses = listOf(Course(id = "long", name = "数字图像处理与分析")),
                routines = listOf(TimeRoutine(sections = elevenSections)),
                sessions =
                    listOf(
                        CourseSession(
                            id = "short", courseId = "long", day = 1,
                            startSection = 1, endSection = 1, place = "教学楼A305", teacher = "测试教师",
                        )
                    ),
            )
        showGrid(prefs, schedule)
        for ((font, cardScale, theme) in listOf(
            Triple(1f, 1f, "light"), Triple(1f, 1.35f, "light"), Triple(1.3f, 1.4f, "dark"))) {
            ui.runOnIdle { prefs.value = prefs.value.copy(fontScale = font, cardTextScale = cardScale, theme = theme) }
            ui.waitForIdle()
            ui.onAllNodesWithText("午休").assertCountEquals(0)
            val card = hasTestTag("grid-course-short@$monday")
            ui.onNode(card).assertExists()
            val bounds = ui.onNode(card).fetchSemanticsNode().boundsInRoot
            val texts =
                ui.onAllNodes(hasAnyAncestor(card) and hasText("", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes()
            assertTrue("至少应显示课名", texts.isNotEmpty())
            for (node in texts) {
                val layouts = mutableListOf<TextLayoutResult>()
                val target = node.config.getOrNull(SemanticsProperties.Text)!!.first().text
                ui.onNode(hasAnyAncestor(card) and hasText(target), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue(
                    "字体=$font 缩放=$cardScale 时出现半行文字：$layout",
                    layout.multiParagraph.height <= layout.size.height + 0.5f,
                )
                assertTrue("文字必须留在课程卡内", node.boundsInRoot.bottom <= bounds.bottom + 0.5f)
            }
            shot("layout-card-$font-$cardScale-$theme.png")
        }
    }

    @Test
    fun narrowHeaderDoesNotOverlapActionsAtLargeFontSize() {
        val prefs = mutableStateOf(Preferences(theme = "light", fontScale = 1.6f))
        ui.setContent {
            VanTheme(prefs.value) {
                Box(Modifier.width(280.dp)) {
                    TimetableHeader(gridSchedule(), 18, listOf(gridSchedule()), false, false, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        ui.waitForIdle()
        val week = ui.onNodeWithTag("week-selector").fetchSemanticsNode().boundsInRoot
        for (label in listOf("导入课表", "添加课程", "课表菜单")) {
            val action = ui.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue("周次与 $label 重叠", week.right <= action.left + 0.5f)
        }
        ui.onNodeWithText("第18周", useUnmergedTree = true).assertIsDisplayed()
        shot("layout-header-narrow-large-font.png")
    }
}

/** 按前缀筛选 testTag：Compose 自带的 hasTestTag 只支持精确匹配。 */
private fun hasTestTagMatching(predicate: (String) -> Boolean) =
    SemanticsMatcher("testTag matches") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let(predicate) == true
    }