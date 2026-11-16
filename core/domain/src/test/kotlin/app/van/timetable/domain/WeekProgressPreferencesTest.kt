package app.van.timetable.domain

import org.junit.Assert.*
import org.junit.Test

class WeekProgressPreferencesTest {
    @Test fun olderSettingsDefaultToVisible() {
        val old = Codec.json.decodeFromString(Preferences.serializer(), "{\"theme\":\"dark\"}")
        assertTrue(old.showWeekProgress)
        assertEquals("dark", old.theme)
    }

    @Test fun fullBackupPreservesHiddenProgressBar() {
        val prefs = Preferences(showWeekProgress = false, fontScale = 1.15f)
        val schedule = Schedule(week1Monday = "2026-10-26")
        val restored = Codec.restore(Codec.backup(Backup(schedules = listOf(schedule), preferences = prefs)))
        assertEquals(prefs, restored.preferences)
    }
}
