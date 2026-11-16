// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.*
import java.time.*

@Composable
fun DayAdjustments(s: Schedule, vm: VanViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of(s.zone)).toString()) }
    var source by rememberSaveable { mutableStateOf("") }
    var stop by rememberSaveable { mutableStateOf(false) }
    var globalFollow by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var byWeek by rememberSaveable { mutableStateOf(false) }
    var targetWeek by rememberSaveable {
        mutableStateOf(
            CourseEngine.week(s, LocalDate.parse(date)).coerceIn(1, s.maxWeeks).toString()
        )
    }
    var targetDay by rememberSaveable {
        mutableStateOf(LocalDate.parse(date).dayOfWeek.value.toString())
    }
    var sourceWeek by rememberSaveable { mutableStateOf(targetWeek) }
    var sourceDay by rememberSaveable { mutableStateOf(targetDay) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!byWeek, { byWeek = false }, label = { Text("按日期") })
            FilterChip(byWeek, { byWeek = true }, label = { Text("按周次和星期") })
        }
        if (byWeek)
            WeekDayFields(
                "目标",
                targetWeek,
                targetDay,
                { targetWeek = it },
                { targetDay = it },
            )
        else CalendarDateField("目标日期（这一天上课）", date, { date = it })
        Toggle("这一天停课", stop, { stop = it })
        if (!stop) {
            if (byWeek)
                WeekDayFields(
                    "来源",
                    sourceWeek,
                    sourceDay,
                    { sourceWeek = it },
                    { sourceDay = it },
                )
            else CalendarDateField("来源日期（原课程安排）", source, { source = it })
        }
        Muted("目标日改上来源日的课，来源日仍保留原安排；如需移走来源日课程，再将来源日设为停课。")
        if (byWeek) {
            val preview =
                runCatching {
                        val to = CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                        if (stop) "$to 停课"
                        else
                            "$to 上 ${CourseEngine.dateAt(s, sourceWeek.toInt(), sourceDay.toInt())} 的课"
                    }
                    .getOrNull()
            if (preview != null) Muted(preview)
        }
        Toggle(
            "调休跟随绝对日期（跨课表生效）",
            globalFollow,
            { globalFollow = it },
            "开启后，切换其他课表时该日期仍按此次调休上对应日期的课",
        )
        Muted("仅调整整日安排时，整日替换读取来源日期的原始课表。")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val to =
                            if (byWeek)
                                CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                                    .toString()
                            else date
                        val from =
                            if (stop) null
                            else if (byWeek)
                                CourseEngine.dateAt(s, sourceWeek.toInt(), sourceDay.toInt())
                                    .toString()
                            else source
                        require(stop || to != from) { "来源和目标不能是同一天" }
                        val r = DayOverride(to, from, stop)
                        val candidate =
                            s.copy(dayOverrides = s.dayOverrides.filterNot { it.date == to } + r)
                        Validation.requireValid(candidate)
                        error = ""
                        if (globalFollow) {
                            vm.prefs { prefs ->
                                prefs.copy(
                                    globalDayOverrides =
                                        prefs.globalDayOverrides.filterNot { it.date == to } + r
                                )
                            }
                        } else {
                            vm.save(candidate)
                        }
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存整日安排")
        }
        Text("全局调休（跨课表跟随绝对日期）", fontWeight = FontWeight.Medium)
        if (state.preferences.globalDayOverrides.isEmpty()) Muted("暂无全局调休")
        state.preferences.globalDayOverrides
            .sortedBy { it.date }
            .forEach { r ->
                Action("${r.date} ${if (r.cancelled) "全局停课" else "上 ${r.sourceDate} 的课"}", "跨课表生效 · 点击撤销") {
                    vm.prefs { prefs -> prefs.copy(globalDayOverrides = prefs.globalDayOverrides - r) }
                }
            }
        Text("当前课表已有调整", fontWeight = FontWeight.Medium)
        s.dayOverrides
            .sortedBy { it.date }
            .forEach { r ->
                Action("${r.date} ${if(r.cancelled)"停课"else"上 ${r.sourceDate} 的课"}", "点击撤销") {
                    vm.save(s.copy(dayOverrides = s.dayOverrides - r))
                }
            }
        s.sessionOverrides.forEach { r ->
            val c =
                s.sessions
                    .firstOrNull { it.id == r.sessionId }
                    ?.let { n -> s.courses.firstOrNull { it.id == n.courseId } }
            Action(
                "${r.originalDate} ${c?.name.orEmpty()}",
                if (r.cancelled) "取消这一次 · 点击撤销" else "调整至 ${r.targetDate?:r.originalDate} · 点击撤销",
            ) {
                vm.save(s.copy(sessionOverrides = s.sessionOverrides - r))
            }
        }
    }
}

@Composable
fun SingleAdjustment(
    s: Schedule,
    sessionId: String,
    date: String,
    vm: VanViewModel,
    back: () -> Unit,
) {
    val original =
        s.sessionOverrides.firstOrNull { it.sessionId == sessionId && it.originalDate == date }
    var target by rememberSaveable { mutableStateOf(original?.targetDate ?: date) }
    var start by rememberSaveable { mutableStateOf(original?.start.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(original?.end.orEmpty()) }
    var place by rememberSaveable { mutableStateOf(original?.place.orEmpty()) }
    var cancel by rememberSaveable { mutableStateOf(original?.cancelled ?: false) }
    var error by remember { mutableStateOf("") }
    var byWeek by rememberSaveable { mutableStateOf(false) }
    var targetWeek by rememberSaveable {
        mutableStateOf(
            CourseEngine.week(s, LocalDate.parse(target)).coerceIn(1, s.maxWeeks).toString()
        )
    }
    var targetDay by rememberSaveable {
        mutableStateOf(LocalDate.parse(target).dayOfWeek.value.toString())
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Muted("原上课日期：$date。只影响这一次，其他周次保持原安排。")
        Toggle("取消这一次课程", cancel, { cancel = it })
        if (!cancel) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!byWeek, { byWeek = false }, label = { Text("按日期") })
                FilterChip(byWeek, { byWeek = true }, label = { Text("按周次和星期") })
            }
            if (byWeek)
                WeekDayFields(
                    "目标",
                    targetWeek,
                    targetDay,
                    { targetWeek = it },
                    { targetDay = it },
                )
            else CalendarDateField("调整到日期", target, { target = it })
            Field(start, { start = it }, "新的开始时间（可选 HH:mm）")
            Field(end, { end = it }, "新的结束时间（可选 HH:mm）")
            Field(place, { place = it }, "新的地点（空白沿用原地点）")
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val r =
                            SessionOverride(
                                sessionId,
                                date,
                                if (cancel) null
                                else if (byWeek)
                                    CourseEngine.dateAt(s, targetWeek.toInt(), targetDay.toInt())
                                        .toString()
                                else target,
                                cancel,
                                start.takeIf { it.isNotBlank() },
                                end.takeIf { it.isNotBlank() },
                                place.takeIf { it.isNotBlank() },
                            )
                        val v =
                            s.copy(
                                sessionOverrides =
                                    s.sessionOverrides.filterNot {
                                        it.sessionId == sessionId && it.originalDate == date
                                    } + r
                            )
                        Validation.requireValid(v)
                        vm.save(v, after = back)
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存单次调整")
        }
    }
}

@Composable
private fun WeekDayFields(
    prefix: String,
    week: String,
    day: String,
    onWeek: (String) -> Unit,
    onDay: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(week, onWeek, "${prefix}周次", Modifier.weight(1f).testTag("${prefix}-week"))
        Field(day, onDay, "${prefix}星期（1–7）", Modifier.weight(1f).testTag("${prefix}-day"))
    }
}