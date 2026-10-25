// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Resolved dates are shared with the band; no second timetable engine runs on the wrist. */
@Serializable data class BandLesson(
    val id: String, val name: String, val place: String, val teacher: String,
    val sections: String, val start: String, val end: String,
    val startsAt: Long, val endsAt: Long, val color: String, val adjusted: Boolean,
)
@Serializable data class BandDay(
    val date: String, val weekday: Int, val week: Int, val startsAt: Long, val endsAt: Long,
    val notice: String = "", val lessons: List<BandLesson> = emptyList(),
    val special: List<String> = emptyList(),
)
@Serializable data class BandSnapshot(
    val format: String = "vanband", val version: Int = 1,
    val scheduleId: String = "", val scheduleName: String = "", val zone: String = "Asia/Shanghai",
    val generatedAt: Long, val from: String = "", val through: String = "", val syncedLabel: String = "",
    val cleared: Boolean = false, val probe: Boolean = false,
    val days: List<BandDay> = emptyList(),
)
@Serializable data class BandMessage(
    val format: String = "vanband", val version: Int = 1, val type: String,
    val requestId: String = "", val index: Int = 0, val count: Int = 0,
    val hash: String = "", val data: String = "", val error: String = "",
)

object BandSnapshots {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    const val WINDOW_DAYS = 14
    const val MAX_CHUNKS = 2048
    const val MAX_CHARS = 480_000

    fun create(schedule: Schedule?, now: Instant = Instant.now(), probe: Boolean = false): BandSnapshot {
        if (schedule == null) return BandSnapshot(generatedAt = now.toEpochMilli(), cleared = true)
        val zone = ZoneId.of(schedule.zone)
        val first = now.atZone(zone).toLocalDate()
        val days = (0 until WINDOW_DAYS).map { offset ->
            val date = first.plusDays(offset.toLong())
            val week = CourseEngine.week(schedule, date)
            val notice = CourseEngine.holiday(schedule, date)?.let { "放假 · ${it.name}" }
                ?: if (schedule.dayOverrides.any { it.date == date.toString() && it.cancelled }) "整日停课" else ""
            BandDay(date.toString(), date.dayOfWeek.value, week,
                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), notice,
                CourseEngine.resolveDay(schedule, date).map { o ->
                    BandLesson(o.id, o.course.name, o.place, o.session.teacher,
                        if (o.session.mode == TimeMode.SECTIONS) "${o.session.startSection}–${o.session.endSection}节" else "自定义时间",
                        o.start.toString(), o.end.toString(), o.startsAt(zone).toEpochMilli(),
                        o.endsAt(zone).toEpochMilli(), color(o.course), o.adjusted)
                },
                if (week in 1..schedule.maxWeeks) CourseEngine.special(schedule, week) else emptyList())
        }
        return BandSnapshot(scheduleId = schedule.id, scheduleName = schedule.name, zone = schedule.zone,
            generatedAt = now.toEpochMilli(), from = first.toString(), through = days.last().date,
            syncedLabel = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")),
            probe = probe, days = days)
    }

    // FNV-1a over UTF-16 code units, identical in Kotlin and the Vela JavaScript receiver.
    fun hash(text: String): String {
        var value = 2166136261L
        text.forEach { value = ((value xor it.code.toLong()) * 16777619L) and 0xffffffffL }
        return value.toString(16).padStart(8, '0')
    }

    fun chunks(snapshot: BandSnapshot, requestId: String): List<BandMessage> {
        val text = json.encodeToString(snapshot)
        require(text.length <= MAX_CHARS) { "课表数据过大，暂不能同步到手环" }
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 240, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            parts += text.substring(start, end)
            start = end
        }
        require(parts.size <= MAX_CHUNKS)
        val hash = hash(text)
        return parts.mapIndexed { i, part -> BandMessage(type = "chunk", requestId = requestId,
            index = i, count = parts.size, hash = hash, data = part) }
    }

    private fun color(course: Course): String {
        val hex = (CoursePalette.argb(course, isDark = true) and 0xffffff).toString(16).padStart(6, '0')
        return "#$hex"
    }
}
