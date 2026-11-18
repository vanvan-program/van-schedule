package app.van.timetable

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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
class TimetableChromeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = (ui.activity.application as VanApplication).store
    private var previous: Backup? = null
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val sections = listOf("08:10" to "08:55", "09:05" to "09:50", "10:30" to "11:15", "11:25" to "12:10",
        "14:10" to "14:55", "15:05" to "15:50", "16:30" to "17:15", "17:25" to "18:10", "19:15" to "20:00",
        "20:10" to "20:55", "21:05" to "21:50").mapIndexed { i, p -> Section(i + 1, p.first, p.second) }
    private lateinit var schedule: Schedule
    @Before fun seed() {
        previous = runBlocking { Backup(schedules = store.all(), preferences = store.prefs()) }
        val courses = listOf(Course(id="image-fix", name="数字图像处理", color=1), Course(id="signals-fix", name="数字信号处理",color=4))
        schedule = Schedule(id="chrome-fix", name="11节课布局验证", week1Monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            courses=courses, routines=listOf(TimeRoutine(sections=sections)), sessions=listOf(
                CourseSession(id="image-fix-slot",courseId=courses[0].id,day=2,startSection=1,endSection=2),
                CourseSession(id="signals-fix-slot",courseId=courses[1].id,day=3,startSection=7,endSection=8)))
        runBlocking {
            store.save(schedule)
            store.updatePreferences { Preferences(activeScheduleId=schedule.id,theme="light",fitWeek=true) }
        }
        instrumentation.runOnMainSync {
            ui.activity.startActivity(Intent(ui.activity,MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("openTimetable",true).putExtra("scheduleId",schedule.id).putExtra("date",today.toString()))
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(schedule.name).fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() { previous?.let { runBlocking { store.restore(it) } } }
    private fun shot(name:String) { ui.waitForIdle(); UiDevice.getInstance(instrumentation).takeScreenshot(File(ui.activity.getExternalFilesDir(null),name)) }

    @Test fun elevenSectionTimeLabelsNeverLoseTheirLastLine() {
        val failures=mutableListOf<String>()
        val measurements=mutableListOf<String>()
        for ((font,fit,row) in listOf(Triple(1f,true,56),Triple(1.15f,true,56),Triple(1.3f,true,56),Triple(1.3f,false,36))) {
            runBlocking { store.updatePreferences { it.copy(fontScale=font,fitWeek=fit,rowHeight=row) } }
            ui.waitForIdle()
            for (section in sections) {
                val parent = hasTestTag("section-${section.number}") and hasAnyAncestor(hasTestTag("week-grid-1"))
                ui.onNode(parent).performScrollTo()
                for (text in listOf(section.number.toString(),section.start,section.end)) {
                    val node=ui.onNode(hasText(text) and hasAnyAncestor(parent),useUnmergedTree=true)
                    val layouts=mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val result=layouts.single()
                    measurements += "$font,$fit,$row,${section.number},$text,${result.size.height},${result.layoutInput.constraints},${result.hasVisualOverflow},widthOverflow=${result.didOverflowWidth},heightOverflow=${result.didOverflowHeight},paragraphHeight=${result.multiParagraph.height}"
                    // BasicText's compact layout can report paragraph-width overflow even
                    // when every glyph fits. Check actual line width and paragraph height.
                    val lineWidth = result.getLineRight(0) - result.getLineLeft(0)
                    val clipped = result.lineCount != 1 || result.multiParagraph.height > result.size.height + 0.5f || lineWidth > result.size.width + 0.5f
                    if(clipped) failures += "font=$font fit=$fit row=$row section=${section.number} text=$text size=${result.size} lineWidth=$lineWidth paragraphHeight=${result.multiParagraph.height}"
                }
            }
            val endBounds = ui.onNode(hasText(sections.last().end) and hasAnyAncestor(hasTestTag("week-grid-1")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val footerBounds = ui.onNodeWithTag("week-scrubber").fetchSemanticsNode().boundsInRoot
            assertTrue("Time labels must remain above the progress bar", endBounds.bottom <= footerBounds.top)
            shot("chrome-time-$font-$fit.png")
        }
        File(ui.activity.getExternalFilesDir(null),"chrome-label-measurements.txt").writeText(measurements.joinToString("\n"))
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

    @Test fun progressBarShowsWeekLabelOnlyWhileDraggingAndUsesSmallRoundThumb() {
        // 静止时仍是原来那条干净进度条，没有标签。
        ui.onNodeWithTag("week-scrubber-label").assertDoesNotExist()
        val thumb=ui.onNodeWithTag("week-scrubber-thumb",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val dp=ui.activity.resources.displayMetrics.density
        assertEquals(thumb.width,thumb.height,0.5f)
        assertTrue("Thumb must be a small dot",thumb.height<=14f*dp)
        // 0.5.9：拖动期间在拇指上方浮出「第N周」，抬手后消失。
        // 必须真的拖一下才会产生 onValueChange（draft 不为空），标签才有内容。
        val scrubber = ui.onNodeWithTag("week-scrubber")
        scrubber.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-24f, 0f))
        }
        ui.waitForIdle()
        val label = ui.onNodeWithTag("week-scrubber-label").assertExists().fetchSemanticsNode()
        val labelText = label.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue("拖动时标签应显示周次，实际：$labelText", labelText.startsWith("第") && labelText.endsWith("周"))
        scrubber.performTouchInput { up() }
        ui.waitForIdle()
        ui.onNodeWithTag("week-scrubber-label").assertDoesNotExist()
        ui.onNodeWithTag("week-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        ui.onNodeWithText("第9周 ▾").assertExists()
        ui.onNodeWithTag("week-scrubber").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"第9周"))
        shot("chrome-progress-light.png")
        ui.onNodeWithTag("week-viewport").performTouchInput { swipeLeft() }
        ui.onNodeWithText("第10周 ▾").assertExists()
        ui.onNodeWithTag("jump-today").performClick()
        ui.onNodeWithText("第1周 ▾").assertExists()
        runBlocking { store.updatePreferences { it.copy(theme="dark",fontScale=1.3f) } }
        shot("chrome-progress-dark.png")
        assertEquals(schedule,runBlocking { store.all().first { it.id==schedule.id } })
    }
    @Test fun progressSwitchPersistsRestoresAndReleasesSpaceInBothModes() {
        val before = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot.height
        fun settings() {
            ui.onNodeWithContentDescription("课表菜单").performClick()
            ui.onNodeWithText("外观设置").performClick()
            ui.onNodeWithText("视图与动效").performScrollTo().performClick()
            ui.onNodeWithTag("week-progress-setting").performScrollTo()
        }
        fun switch() = ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag("week-progress-setting")))
        settings()
        switch().assertIsOn().performClick()
        ui.waitUntil(10000) { !runBlocking { store.prefs() }.showWeekProgress }
        switch().assertIsOff()
        shot("progress-toggle-settings-off.png")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("week-scrubber").assertDoesNotExist()
        val after = ui.onNodeWithTag("week-viewport").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Hidden control must release its layout space", after - before >= 40f * ui.activity.resources.displayMetrics.density)
        shot("progress-toggle-hidden.png")
        val backup = runBlocking { Codec.restore(store.backup()) }
        assertFalse(backup.preferences!!.showWeekProgress)
        runBlocking {
            store.updatePreferences { it.copy(showWeekProgress = true) }
            store.restore(backup)
        }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("week-scrubber").assertDoesNotExist()
        ui.onNodeWithContentDescription("课表菜单").performClick()
        ui.onNodeWithText("切换为日列表").performClick()
        ui.onNodeWithTag("week-scrubber").assertDoesNotExist()
        settings()
        switch().assertIsOff().performClick()
        ui.waitUntil(10000) { runBlocking { store.prefs() }.showWeekProgress }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("week-scrubber").assertExists()
        ui.onNodeWithTag("week-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        ui.onNodeWithText("第3周 ▾").assertExists()
        assertEquals(schedule,runBlocking { store.all().first { it.id == schedule.id } })
    }

}
