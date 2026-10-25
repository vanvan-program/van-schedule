package app.van.timetable

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.*
import app.van.timetable.ui.*
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BandScreenTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as VanApplication
    @Before fun setup() { ui.runOnIdle { app.band.enable(false) } }

    @Test fun entryDisplaysActiveTimetableAndSyncActions() {
        val schedule = Schedule(name = "手环合成课表", week1Monday = "2026-10-19")
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences()) { BandScreen(VanState(schedules = listOf(schedule), preferences = Preferences(activeScheduleId = schedule.id), ready = true)) } } }
        ui.onNodeWithTag("band-schedule").assertTextEquals("同步课表：手环合成课表")
        ui.onNodeWithTag("band-sync").assertIsEnabled()
        ui.waitForIdle()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "030-band-light.png")))
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences(theme = "dark")) { BandScreen(VanState(schedules = listOf(schedule), preferences = Preferences(activeScheduleId = schedule.id), ready = true)) } } }
        ui.waitForIdle()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(ui.activity.getExternalFilesDir(null), "030-band-dark.png")))
    }

    @Test fun noActiveTimetableDisablesSyncButAllowsProbe() {
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences()) { BandScreen(VanState(ready = true)) } } }
        ui.onNodeWithTag("band-sync").assertIsNotEnabled()
        ui.onNodeWithTag("band-probe").assertIsEnabled()
    }

    @Test fun clearRequiresConfirmationAndCancelKeepsPhoneData() {
        val before = runBlocking { app.store.backup() }
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences()) { BandScreen(VanState(ready = true)) } } }
        ui.onNodeWithTag("band-clear").performScrollTo().performClick()
        ui.onNodeWithText("清除手环缓存，手机课程保留", substring = true).assertExists()
        ui.onNodeWithText("取消").performClick()
        assertEquals(before, runBlocking { app.store.backup() })
    }

    @Test fun missingCompanionNeverReportsSuccessfulSyncOrChangesTimetable() {
        val before = runBlocking { app.store.backup() }
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences()) { BandScreen(VanState(ready = true)) } } }
        ui.onNodeWithTag("band-probe").performScrollTo().performClick()
        ui.waitUntil(20000) { !app.band.status.value.busy }
        val status = app.band.status.value
        assertFalse(status.connected)
        assertFalse(status.message.contains("已确认保存"))
        assertFalse(status.message.contains("示例课程已在手环保存"))
        assertEquals(before, runBlocking { app.store.backup() })
    }

    @Test fun openingPageChecksConnectionWithAutomaticSyncOffAndRefreshCanRetry() {
        val before = runBlocking { app.store.backup() }
        ui.runOnIdle { ui.activity.setContent { VanTheme(Preferences()) { BandScreen(VanState(ready = true)) } } }
        ui.waitUntil(20000) { !app.band.status.value.checking && app.band.status.value.connectionMessage.contains("请先安装") }
        assertFalse(app.band.status.value.enabled)
        assertEquals("自动同步已关闭，手环保留上次缓存", app.band.status.value.message)
        ui.onNodeWithTag("band-check").performClick()
        ui.waitUntil(20000) { !app.band.status.value.checking }
        assertFalse(app.band.status.value.connected)
        assertEquals(before, runBlocking { app.store.backup() })
    }
}
