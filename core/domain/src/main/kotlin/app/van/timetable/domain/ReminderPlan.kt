// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 提醒时间的纯计算：不碰 AlarmManager、通知、SharedPreferences。
 *
 * 提醒这块以前只能靠真机或模拟器验证，跨天、假期、调课、学期结束后的整日补课
 * 这些时间边界很难复现。把「哪些课要提醒、什么时候提醒、下一次该在什么时候醒」
 * 抽到这里之后，可以用时间推进的单元测试直接覆盖。
 */
object ReminderPlan {

    /** 一条待发提醒：提醒哪节课，按哪个时刻触发。 */
    data class Reminder(val occurrence: Occurrence, val at: Instant)

    /**
     * 某一天需要提醒的课程。
     *
     * 冲突组里只留主课（与课程页显示一致），并且课程本身要开着提醒。
     * 假期、非本周、学期结束后补课这些判断都在 [CourseEngine.resolveDay] 里。
     */
    fun items(
        s: Schedule,
        date: LocalDate,
        overrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> {
        val all = CourseEngine.resolveDay(s, date, overrides)
        val excluded =
            CourseEngine.conflicts(all)
                .flatMap { group ->
                    val main = CourseEngine.main(group)
                    group.filter { it.id != main.id }
                }
                .mapTo(hashSetOf()) { it.id }
        return all.filter { it.id !in excluded && it.course.reminder }
    }

    /**
     * 从 [today] 起 [days] 天的提醒（含今天），按触发时刻升序。
     *
     * 提前量取课程自己的设置，没设就用课表默认值 [leadMinutes]。
     */
    fun upcoming(
        s: Schedule,
        today: LocalDate,
        zone: ZoneId,
        leadMinutes: Int,
        days: Int = 3,
        overrides: List<DayOverride> = emptyList(),
    ): List<Reminder> =
        (0 until days)
            .flatMap { offset -> items(s, today.plusDays(offset.toLong()), overrides) }
            .map { occurrence ->
                val lead = occurrence.course.leadMinutes ?: leadMinutes
                Reminder(occurrence, occurrence.startsAt(zone).minusSeconds(lead * 60L))
            }
            .sortedBy { it.at }

    /**
     * 下一次需要唤醒闹钟的时刻：候选里严格晚于 [now] 的最小值。
     *
     * 候选由调用方汇总（次日零点、提醒时刻、状态通知与小组件的刷新边界、课程起止）。
     */
    fun nextWake(now: Instant, candidates: Collection<Instant>): Instant? =
        candidates.filter { it > now }.minOrNull()
}
