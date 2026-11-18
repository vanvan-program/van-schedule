package app.van.timetable

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import java.io.File
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Version032UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val c = Course(id = "main032", name = "数字图像处理")
    private val lab = Course(id = "lab032", name = "数字图像处理 · 实验课", kind = "实验课", parentCourseId = c.id)
    private val exam = Course(id = "exam032", name = "数字图像处理 · 期末考试", kind = "期末考试", parentCourseId = c.id)
    private val n = CourseSession(id = "slot032", courseId = c.id, day = today.dayOfWeek.value)
    private lateinit var seeded: Schedule
    @Before fun preserve() { previous = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) } }
    @After fun restore() { previous?.let { runBlocking { store.restore(it) } } }
    private fun seed(once: Boolean = false, last: Int = 2, dark: Boolean = false, large: Boolean = false) {
        seeded = Schedule(id = "version032", name = "0.3.2 验证课表", week1Monday = monday.toString(),
            courses = listOf(c, lab, exam), sessions = listOf(
                n.copy(date = if (once) today.toString() else null, endSection = last),
                n.copy(id = "labslot032", courseId = lab.id, date = today.toString(), weeks = emptySet(), startSection = 5, endSection = 6, place = "实验楼401"),
                n.copy(id = "examslot032", courseId = exam.id, date = today.plusDays(2).toString(), weeks = emptySet(), mode = TimeMode.CLOCK, start = "12:00", end = "13:00"),
            ))
        runBlocking {
            store.save(seeded)
            store.updatePreferences { Preferences(activeScheduleId = seeded.id, theme = if (dark) "dark" else "light", fontScale = if (large) 1.3f else 1f) }
        }
        instrumentation.runOnMainSync {
            ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("openTimetable", true).putExtra("scheduleId", seeded.id).putExtra("date", today.toString()))
        }
        ui.waitUntil(15000) { ui.onAllNodesWithText(seeded.name).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun saved() = runBlocking { store.all().first { it.id == seeded.id } }
    private fun shot(name: String) {
        ui.waitForIdle(); device.waitForIdle(300)
        assertTrue(device.takeScreenshot(File(ui.activity.getExternalFilesDir(null), "032-$name.png")))
    }
    private fun editor() {
        ui.onNodeWithTag("grid-course-${n.id}@$today").performScrollTo().performClick()
        ui.onNodeWithText("编辑整门课程").performScrollTo().performClick()
    }
    private fun routines() {
        ui.onNodeWithTag("tab-2").performClick()
        ui.onNodeWithText("作息管理").performScrollTo().performClick()
    }

    @Test fun groupedCourseSearchIncludesChildrenAndDateOnlyWeekFilter() {
        seed(once = true)
        ui.onNodeWithText("课程").performClick()
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        ui.onNodeWithTag("course-group-${lab.id}").assertDoesNotExist()
        ui.onNodeWithTag("course-group-${exam.id}").assertDoesNotExist()
        ui.onNodeWithText("搜索课程、老师、地点").performTextInput("实验楼401")
        ui.onNodeWithTag("course-group-${c.id}").performClick()
        ui.onNodeWithText("正课").assertExists()
        ui.onNodeWithText("实验课").performScrollTo().assertExists()
        ui.onNodeWithText("期末考试", substring = false).performScrollTo().assertExists()
        shot("group-details")
        ui.onNodeWithText("编辑实验课").performScrollTo().performClick()
        ui.onNodeWithText("课程名称").assertTextContains(lab.name)
        device.pressBack(); ui.onNodeWithText("确认").performClick()
        ui.onNodeWithText("课程详情").assertExists()
        assertEquals(seeded, saved())
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("搜索课程、老师、地点").performTextReplacement("实验楼401")
        ui.onNode(isToggleable()).performClick()
        ui.onNode(isToggleable()).assertIsOn()
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        device.pressBack()
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        shot("group-list")
    }

    @Test fun scrubberChangesWeekAndStaysInSyncWithPagingAndRecreation() {
        seed()
        ui.onNodeWithTag("week-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        ui.onNodeWithText("第9周 ▾").assertExists()
        ui.onNodeWithTag("week-scrubber-label").assertDoesNotExist()
        shot("week-slider")
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第10周 ▾").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("第10周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithTag("week-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        ui.onNodeWithText("第3周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
    }

    @Test fun weekGridDragCancelAndRecreationSaveToCorrectSession() {
        seed(); editor()
        ui.onNodeWithTag("edit-weeks").performScrollTo().performClick()
        ui.onNodeWithTag("edit-week-1").performClick()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("edit-weeks").performClick()
        ui.onNodeWithTag("edit-week-1").assertIsSelected()
        val grid = ui.onNodeWithTag("week-multiselect-grid").fetchSemanticsNode().boundsInRoot
        val first = ui.onNodeWithTag("edit-week-1").fetchSemanticsNode().boundsInRoot.center - grid.topLeft
        val fifth = ui.onNodeWithTag("edit-week-5").fetchSemanticsNode().boundsInRoot.center - grid.topLeft
        ui.onNodeWithTag("week-multiselect-grid").performTouchInput { swipe(first, fifth, 1000) }
        (1..5).forEach { ui.onNodeWithTag("edit-week-$it").assertIsNotSelected() }
        ui.onNodeWithTag("edit-week-6").assertIsSelected()
        ui.activityRule.scenario.recreate()
        (1..5).forEach { ui.onNodeWithTag("edit-week-$it").assertIsNotSelected() }
        shot("week-picker")
        ui.onNodeWithTag("save-weeks").performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().sessions.first { it.id == n.id }.weeks == (6..20).toSet() }
        assertEquals(seeded.sessions.filter { it.id != n.id }, saved().sessions.filter { it.id != n.id })
    }

    @Test fun combinedWheelsCancelSaveAndRestoreDraft() {
        seed(); editor()
        ui.onNodeWithTag("course-time").performScrollTo().performClick()
        ui.onNodeWithTag("weekday-wheel").assertExists()
        ui.onNodeWithTag("start-section-wheel").performTouchInput { swipeUp(durationMillis = 400) }
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("course-time").assertTextContains("第1–2节", substring = true)
        ui.onNodeWithTag("course-time").performClick()
        ui.onNodeWithTag("start-section-wheel").performTouchInput { swipeUp(durationMillis = 400) }
        ui.activityRule.scenario.recreate()
        shot("course-time")
        ui.onNodeWithText("保存上课时间").performClick()
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().sessions.first { it.id == n.id }.startSection != 1 }
        val changed = saved().sessions.first { it.id == n.id }
        assertTrue(changed.endSection >= changed.startSection)
    }

    @Test fun onceDateHidesWeekdayAndWeeksInDarkLargeText() {
        seed(once = true, dark = true, large = true); editor()
        ui.onNodeWithTag("edit-weeks").assertDoesNotExist()
        ui.onNodeWithTag("course-time").performScrollTo().performClick()
        ui.onNodeWithTag("weekday-wheel").assertDoesNotExist()
        ui.onNodeWithTag("start-section-wheel").assertExists()
        shot("once-time-dark")
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("clear-once-date").performScrollTo().performClick()
        ui.onNodeWithTag("edit-weeks").assertExists()
        ui.onNodeWithTag("course-time").performScrollTo().performClick()
        ui.onNodeWithTag("weekday-wheel").assertExists()
        ui.onNodeWithText("取消").performClick()
    }

    @Test fun routineWheelsAndSectionCountPersistAndCancelDoesNotChangeDraft() {
        seed(); routines()
        ui.onNodeWithTag("routine-section-1").performClick()
        ui.onNodeWithTag("routine-start-minute").performTouchInput { swipeUp(durationMillis = 500) }
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("08:00–08:45").assertExists()
        ui.onNodeWithTag("routine-section-1").performClick()
        ui.onNodeWithTag("routine-start-minute").performTouchInput { swipeUp(durationMillis = 500) }
        ui.activityRule.scenario.recreate()
        shot("routine-time")
        ui.onNodeWithTag("save-section-time").performClick()
        ui.onNodeWithTag("add-routine-section").performClick()
        ui.onNodeWithText("每天 11 节").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("每天 11 节").assertExists()
        shot("routine-list")
        ui.onNodeWithTag("save-routine").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().routines.first().sections.size == 11 }
        assertNotEquals("08:00", saved().routines.first().sections.first().start)
        assertEquals(seeded.sessions, saved().sessions)
    }

    @Test fun reducingRoutineCannotRemoveSectionsUsedByCoursesAndInvalidTimeIsBlocked() {
        seed(last = 10); routines()
        ui.onNodeWithTag("remove-routine-section").performClick()
        ui.onNodeWithTag("routine-error").performScrollTo().assertTextContains("已有课程使用第10节", substring = true)
        ui.onNodeWithTag("routine-section-2").performScrollTo().performClick()
        ui.onNodeWithTag("routine-start-hour").performTouchInput { swipeUp(durationMillis = 400) }
        ui.onNodeWithTag("save-section-time").assertIsNotEnabled()
        shot("routine-invalid")
        ui.onNodeWithText("取消").performClick()
        assertEquals(seeded, saved())
    }

    @Test fun historyExpandsLatestAndAllVersionsHaveNumberedCategories() {
        seed(); ui.onNodeWithTag("tab-2").performClick()
        ui.onNodeWithText("帮助与关于").performScrollTo().performClick()
        ui.onNodeWithText("历史版本更新").performScrollTo().performClick()
        // 首屏最新版本的第一条：断言确实展开了最新更新记录
        ui.onNodeWithText("1. 【调整】底部导航改成一眼能看出的悬浮岛", substring = true).assertExists()
        shot("changelog")
        val text = ui.activity.assets.open("changelog.txt").bufferedReader().use { it.readText() }
        text.split("## ").filter { it.isNotBlank() }.forEach { entry ->
            entry.lines().drop(1).filter { it.isNotBlank() }.forEachIndexed { index, line ->
                assertTrue(line, line.matches(Regex("${index + 1}\\. 【(新增|优化|修复|调整)】.+")))
            }
        }
    }
    @Test fun singleSectionRoutineAndOneWeekSemesterAllowCreatingCourse() {
        seed()
        seeded = seeded.copy(maxWeeks = 1, courses = emptyList(), sessions = emptyList(),
            routines = seeded.routines.map { it.copy(sections = it.sections.take(1)) })
        runBlocking { store.save(seeded) }
        ui.waitUntil(10000) {
            ui.onAllNodes(hasTestTag("week-scrubber") and !isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("week-scrubber").assertIsNotEnabled()
        ui.onNodeWithContentDescription("添加课程").performClick()
        ui.onNodeWithText("课程名称").performTextInput("单节课程")
        ui.onNodeWithText("保存课程").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().courses.any { it.name == "单节课程" } }
        val session = saved().sessions.single()
        assertEquals(1, session.startSection)
        assertEquals(1, session.endSection)
        assertEquals(setOf(1), session.weeks)
    }

    @Test fun savingAnotherRoutineSelectsItAndKeepsBothRoutines() {
        seed(); routines()
        ui.onNodeWithText("作息名称").performTextReplacement("另一套作息")
        ui.onNodeWithText("保存为另一套作息").performScrollTo().performClick()
        ui.waitUntil(15000) { saved().routines.size == 2 }
        ui.onNode(hasText("另一套作息") and isSelectable()).performScrollTo().assertIsSelected()
        assertEquals(seeded.routines.first(), saved().routines.first())
        assertEquals(seeded.sessions, saved().sessions)
    }

    @Test fun courseGroupsRemainAvailableOutsideSemesterAndDateOnlyLessonsCanMatchThisWeek() {
        seed()
        seeded = seeded.copy(week1Monday = monday.plusWeeks(1).toString())
        runBlocking { store.save(seeded) }
        ui.onNodeWithText("课程").performClick()
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        ui.onNode(isToggleable()).performClick()
        ui.onNode(isToggleable()).assertIsOn()
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        seeded = seeded.copy(week1Monday = monday.minusWeeks(30).toString())
        runBlocking { store.save(seeded) }
        ui.onNodeWithTag("course-group-${c.id}").assertExists()
        seeded = seeded.copy(sessions = seeded.sessions.map {
            if (it.date != null) it.copy(date = today.plusWeeks(10).toString()) else it
        })
        runBlocking { store.save(seeded) }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("course-group-${c.id}").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("没有符合条件的课程").assertExists()
    }

}
