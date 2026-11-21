// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/** 时段标签：按节次 / 按钟点 / 无固定时间。分隔符统一用 en dash，前端各处调用这里。 */
fun CourseSession.timeLabel(): String =
    when (mode) {
        TimeMode.SECTIONS -> "第${startSection}–${endSection}节"
        TimeMode.CLOCK -> "$start–$end"
        TimeMode.UNTIMED -> "无固定时间"
    }

fun formatWeeks(weeks: Set<Int>): String {
    val sorted = weeks.sorted()
    if (sorted.isEmpty()) return "未设置"
    val groups = mutableListOf<String>()
    var first = sorted.first()
    var last = first
    fun flush() {
        groups += if (first == last) "$first" else "$first–$last"
    }
    sorted.drop(1).forEach {
        if (it == last + 1) last = it
        else {
            flush()
            first = it
            last = it
        }
    }
    flush()
    return "第${groups.joinToString("、")}周"
}
