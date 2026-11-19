// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.van.timetable.*
import app.van.timetable.domain.*
import app.van.timetable.widget.Widgets
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Scheduler {
    private val mutex = Mutex()
    private const val REMINDER_ID = 2
    private const val REMINDER_KEYS = "app.van.timetable.reminderKeys"

    private fun app(c: Context) = c.applicationContext as VanApplication

    fun allowed(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun post(c: Context, id: Int, notification: Notification): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    c,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return false
        return try {
            NotificationManagerCompat.from(c).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun channels(c: Context) {
        val m = c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel("courses", "课前提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
        m.createNotificationChannel(
            NotificationChannel("status", "课程状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun testNotification(c: Context) {
        channels(c)
        if (!allowed(c)) return
        post(
            c,
            3,
            NotificationCompat.Builder(c, "courses")
                .setSmallIcon(R.drawable.ic_van)
                .setContentTitle("van课表 · 通知测试")
                .setContentText("操作系统 · 示例楼A214 · 10:00开始")
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun content(c: Context, scheduleId: String, o: Occurrence) =
        PendingIntent.getActivity(
            c,
            50,
            Intent(c, MainActivity::class.java)
                .putExtra("scheduleId", scheduleId)
                .putExtra("courseId", o.course.id)
                .putExtra("date", o.date.toString())
                .putExtra("sessionId", o.session.id)
                .putExtra("originalDate", CourseEngine.originalDate(o))
                .setData(Uri.Builder().scheme("van").authority("course").appendPath(scheduleId)
                    .appendPath(o.id).appendPath(o.date.toString()).build()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun reminderItems(
        s: Schedule,
        date: LocalDate,
        globalDayOverrides: List<DayOverride> = emptyList(),
    ): List<Occurrence> {
        val all = CourseEngine.resolveDay(s, date, globalDayOverrides)
        val groups = CourseEngine.conflicts(all)
        val excluded =
            groups
                .flatMap { g ->
                    val main = CourseEngine.main(g)
                    g.filter { it.id != main.id }
                }
                .map { it.id }
                .toSet()
        return all.filter { it.id !in excluded && it.course.reminder }
    }

    private data class Reminder(val occurrence: Occurrence, val key: String, val at: Instant)

    private fun syncReminders(
        c: Context,
        scheduleId: String,
        zone: ZoneId,
        now: Instant,
        alarmTime: Long?,
        reminders: List<Reminder>,
    ): Instant? {
        val manager = c.getSystemService(NotificationManager::class.java)
        val previous = manager.activeNotifications.firstOrNull { it.id == REMINDER_ID && it.tag == null }
            ?.notification
        val visibleKeys = previous?.extras?.getStringArrayList(REMINDER_KEYS).orEmpty().toSet()
        val cache = c.getSharedPreferences("alarm_state", Context.MODE_PRIVATE)
        val sent = cache.getStringSet("sent", emptySet()).orEmpty()
        val remaining = reminders.filter { it.occurrence.endsAt(zone) > now }
        val fresh = remaining.filter { reminder ->
            reminder.key !in sent && reminder.key !in visibleKeys && reminder.at <= now &&
                (reminder.occurrence.startsAt(zone) > now || reminder.at.toEpochMilli() == alarmTime)
        }
        val freshTimes = fresh.mapTo(hashSetOf()) { it.at }
        val freshKeys = fresh.mapTo(hashSetOf()) { it.key }
        val shown = remaining.filter { reminder ->
            reminder.key in freshKeys || (reminder.key in visibleKeys &&
                (freshTimes.isEmpty() || reminder.at in freshTimes))
        }
        if (shown.isEmpty()) {
            if (previous != null) manager.cancel(REMINDER_ID)
            return null
        }

        val line = shown.joinToString("；") {
            val lesson = it.occurrence
            "${lesson.course.name} · ${lesson.place.ifBlank { "地点待定" }} · ${lesson.start}开始"
        }
        val keys = shown.map { it.key }
        if (keys.toSet() != visibleKeys || line != previous?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()) {
            val posted = post(c, REMINDER_ID,
                NotificationCompat.Builder(c, "courses")
                    .setSmallIcon(R.drawable.ic_van)
                    .setContentTitle(line)
                    .setContentText(if (shown.size > 1) "${shown.size}门课程提醒" else "课前提醒")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                    .setContentIntent(content(c, scheduleId, shown.first().occurrence))
                    .addExtras(Bundle().apply { putStringArrayList(REMINDER_KEYS, ArrayList(keys)) })
                    .setOnlyAlertOnce(fresh.isEmpty())
                    .setAutoCancel(true)
                    .build(),
            )
            if (!posted) return null
            if (fresh.isNotEmpty()) {
                val recent = (sent + freshKeys).sortedByDescending { it.substringAfterLast('/').toLongOrNull() }
                    .take(100).toSet()
                cache.edit().putStringSet("sent", recent).apply()
            }
        }
        return shown.minOf { it.occurrence.endsAt(zone) }
    }

    suspend fun reconcile(c: Context, eventTime: Long? = null) {
        mutex.withLock {
            val store = app(c).store
            val p = store.prefs()
            val active = p.activeScheduleId?.let { id -> store.all().firstOrNull { it.id == id } }
            val alarms = c.getSystemService(AlarmManager::class.java)
            channels(c)
            val zone = ZoneId.of(active?.zone ?: "Asia/Shanghai")
            val now = Instant.now()
            val date = now.atZone(zone).toLocalDate()
            val boundaries = mutableListOf(date.plusDays(1).atStartOfDay(zone).toInstant())
            if (active != null && p.reminders && allowed(c)) {
                val candidates = (0..2).flatMap { days ->
                    reminderItems(active, date.plusDays(days.toLong()), p.globalDayOverrides)
                }.map { occurrence ->
                    val at = occurrence.startsAt(zone)
                        .minusSeconds((occurrence.course.leadMinutes ?: p.leadMinutes) * 60L)
                    Reminder(occurrence, "${active.id}/${occurrence.id}/${at.toEpochMilli()}", at)
                }
                boundaries += candidates.map { it.at }
                syncReminders(c, active.id, zone, now, eventTime, candidates)?.let { boundaries += it }
            } else {
                NotificationManagerCompat.from(c).cancel(REMINDER_ID)
            }
            if (active != null && p.statusNotification && allowed(c)) {
                val next = CourseEngine.next(active, now, p.globalDayOverrides)
                if (next == null) NotificationManagerCompat.from(c).cancel(1)
                else
                    post(
                        c,
                        1,
                        NotificationCompat.Builder(c, "status")
                            .setSmallIcon(R.drawable.ic_van)
                            .setContentTitle("${next.course.name} · ${next.place} · ${next.start}")
                            .setContentText(
                                if (now >= next.startsAt(zone)) "正在上课" else "下一节 · ${next.date}"
                            )
                            .setContentIntent(content(c, active.id, next))
                            .setOnlyAlertOnce(true)
                            .setOngoing(true)
                            .build(),
                    )
            } else NotificationManagerCompat.from(c).cancel(1)
            boundaries += Widgets.boundaries(c, now)
            if (active != null && p.statusNotification && allowed(c))
                (0..1)
                    .flatMap {
                        CourseEngine.resolveDay(active, date.plusDays(it.toLong()), p.globalDayOverrides)
                    }
                    .forEach { o ->
                        boundaries += o.startsAt(zone)
                        boundaries += o.endsAt(zone)
                    }
            val next = boundaries.filter { it > now }.minOrNull() ?: return@withLock
            val intent = Intent(c, AlarmReceiver::class.java).putExtra("at", next.toEpochMilli())
            val pi =
                PendingIntent.getBroadcast(
                    c,
                    1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                    alarms.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next.toEpochMilli(),
                        pi,
                    )
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pi)
            }
            Widgets.updateAll(c, now)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        (c.applicationContext as VanApplication).scope.launch {
            try {
                Scheduler.reconcile(c, i.getLongExtra("at", 0).takeIf { it > 0 })
            } finally {
                pending.finish()
            }
        }
    }
}

class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (
            i.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
        )
            return
        val pending = goAsync()
        (c.applicationContext as VanApplication).scope.launch {
            try {
                Scheduler.reconcile(c)
            } finally {
                pending.finish()
            }
        }
    }
}
