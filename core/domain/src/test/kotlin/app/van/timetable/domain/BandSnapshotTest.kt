package app.van.timetable.domain

import java.time.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BandSnapshotTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val now = Instant.parse("2026-10-23T02:00:00Z")
    private val course = Course(id = "c", name = "数据库😀", customColor = 0xff123456)
    private val session = CourseSession(id = "s", courseId = "c", day = 5, teacher = "老师", place = "A101")
    private fun schedule() = Schedule(id = "t", week1Monday = monday.toString(), courses = listOf(course), sessions = listOf(session))

    @Test fun fourteenDaysUseScheduleZoneAndCarryExactResolvedTimes() {
        val s = schedule()
        val snapshot = BandSnapshots.create(s, now)
        assertEquals(14, snapshot.days.size)
        assertEquals("2026-10-23", snapshot.from)
        assertEquals("2026-11-05", snapshot.through)
        val lesson = snapshot.days.first().lessons.single()
        assertEquals("#123456", lesson.color)
        assertEquals("老师", lesson.teacher)
        assertEquals(CourseEngine.resolveDay(s, LocalDate.parse(snapshot.from)).single().startsAt(ZoneId.of(s.zone)).toEpochMilli(), lesson.startsAt)
        assertEquals("2026-10-22", BandSnapshots.create(s.copy(zone = "America/Los_Angeles"), Instant.parse("2026-10-23T01:00:00Z")).from)
    }

    @Test fun holidayCancelAndMovedLessonMatchExistingEngine() {
        val s = schedule().copy(sessionOverrides = listOf(SessionOverride("s", "2026-10-23", targetDate = "2026-10-24", place = "B202")),
            holidays = listOf(Holiday(name = "放假测试", from = "2026-10-30", through = "2026-10-30")))
        val data = BandSnapshots.create(s, now)
        assertTrue(data.days[0].lessons.isEmpty())
        assertEquals("B202", data.days[1].lessons.single().place)
        assertTrue(data.days[1].lessons.single().adjusted)
        assertEquals("放假 · 放假测试", data.days[7].notice)
        assertTrue(data.days[7].lessons.isEmpty())
        val cancelled = BandSnapshots.create(schedule().copy(dayOverrides = listOf(DayOverride("2026-10-23", cancelled = true))), now)
        assertEquals("整日停课", cancelled.days.first().notice)
        assertTrue(cancelled.days.first().lessons.isEmpty())
    }

    @Test fun chunksReassembleUnicodeAndStaySmallEnoughForMessages() {
        val snapshot = BandSnapshots.create(schedule().copy(courses = listOf(course.copy(name = "课程😀".repeat(80)))), now)
        val chunks = BandSnapshots.chunks(snapshot, "request")
        val text = chunks.joinToString("") { it.data }
        assertEquals(snapshot, BandSnapshots.json.decodeFromString<BandSnapshot>(text))
        assertEquals(BandSnapshots.hash(text), chunks.first().hash)
        assertTrue(chunks.all { BandSnapshots.json.encodeToString(it).toByteArray().size < 1200 })
        assertEquals(chunks.size, chunks.first().count)
        assertTrue(chunks.all { !it.data.last().isHighSurrogate() })
        assertEquals("4f9f2cab", BandSnapshots.hash("hello"))
    }

    @Test fun clearIsExplicitAndBackupStillReadsOldPreferences() {
        assertTrue(BandSnapshots.create(null, now).cleared)
        val original = schedule()
        val backup = Codec.restore(Codec.backup(Backup(schedules = listOf(original), preferences = Preferences())))
        assertEquals(original, backup.schedules.single())
    }
}
