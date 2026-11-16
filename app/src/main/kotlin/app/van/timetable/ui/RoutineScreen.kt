// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.*
import java.time.LocalTime

private fun encodeSections(sections: List<Section>) = sections.joinToString("\n") { "${it.start},${it.end}" }
private fun decodeSections(value: String) = value.lines().mapIndexed { i, line ->
    Section(i + 1, line.substringBefore(','), line.substringAfter(','))
}

@Composable
fun Routines(s: Schedule, onSave: (Schedule) -> Unit) {
    var selected by rememberSaveable(s.id) { mutableStateOf(s.routines.first().id) }
    var pendingRoutine by rememberSaveable(s.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.routines, pendingRoutine) {
        pendingRoutine?.let { id ->
            if (s.routines.any { it.id == id }) { selected = id; pendingRoutine = null }
        }
    }
    val routine = s.routines.firstOrNull { it.id == selected } ?: s.routines.first()
    var name by rememberSaveable(selected) { mutableStateOf(routine.name) }
    var text by rememberSaveable(selected) { mutableStateOf(encodeSections(routine.sections)) }
    val sections = remember(text) { decodeSections(text) }
    var from by rememberSaveable(selected) { mutableStateOf("") }
    var through by rememberSaveable(selected) { mutableStateOf("") }
    var error by rememberSaveable(selected) { mutableStateOf("") }
    var message by rememberSaveable(selected) { mutableStateOf("") }
    var editing by rememberSaveable(selected) { mutableIntStateOf(0) }
    val required = s.sessions.filter { it.mode == TimeMode.SECTIONS }.maxOfOrNull { it.endSection } ?: 1
    fun change(values: List<Section>) { text = encodeSections(values); message = ""; error = "" }
    fun build(new: Boolean) {
        runCatching {
            require(name.isNotBlank()) { "请填写作息名称" }
            require(from.isBlank() == through.isBlank()) { "请选择完整的生效起止日期" }
            val r = TimeRoutine(if (new) newId() else selected, name.trim(), sections)
            val candidate = s.copy(
                routines = if (new) s.routines + r else s.routines.map { if (it.id == selected) r else it },
                routineRules = s.routineRules + if (from.isNotBlank()) listOf(RoutineRule(r.id, from, through)) else emptyList(),
            )
            Validation.requireValid(candidate)
            onSave(candidate)
            if (new) pendingRoutine = r.id
            // Repeated saves must not append the same effective-date rule again.
            from = ""
            through = ""
            message = "已提交保存"
            error = ""
        }.onFailure { error = it.message.orEmpty() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.routines.forEach { r -> FilterChip(selected == r.id, {
                selected = r.id
            }, label = { Text(r.name) }, colors = vanSelectionColors()) }
        }
        Field(name, { name = it; message = "" }, "作息名称")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("每天 ${sections.size} 节", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            IconButton(onClick = {
                if (sections.size <= required) error = "已有课程使用第${required}节，请先调整课程后再减少节数。"
                else change(sections.dropLast(1))
            }, modifier = Modifier.testTag("remove-routine-section"), enabled = sections.size > 1) {
                Icon(Icons.Outlined.Remove, "减少一节")
            }
            IconButton(onClick = {
                val start = LocalTime.parse(sections.last().end).toSecondOfDay() / 60 + 10
                val end = start + 45
                if (end >= 1440) error = "当天剩余时间不足，请先调整最后一节时间。"
                else change(sections + Section(sections.size + 1,
                    LocalTime.of(start / 60, start % 60).toString(), LocalTime.of(end / 60, end % 60).toString()))
            }, modifier = Modifier.testTag("add-routine-section")) { Icon(Icons.Outlined.Add, "增加一节") }
        }
        Panel {
            sections.forEach { section ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("routine-section-${section.number}")
                    .clickable { editing = section.number }, verticalAlignment = Alignment.CenterVertically) {
                    Text("第${section.number}节", Modifier.weight(1f))
                    Text("${section.start}–${section.end}", color = LocalVan.current.muted)
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "编辑第${section.number}节")
                }
            }
        }
        Muted("点击节次设置起止时间；修改后保存当前作息。已有课程使用的节次会保留。")
        CalendarDateField("生效起日（可选）", from, { from = it }, allowClear = true)
        CalendarDateField("生效末日（可选）", through, { through = it }, allowClear = true)
        s.routineRules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.routines.first { it.id == rule.routineId }.name}：${rule.from} 至 ${rule.through}", Modifier.weight(1f))
                TextButton(onClick = { onSave(s.copy(routineRules = s.routineRules - rule)) }) { Text("删除规则") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("routine-error"))
        if (message.isNotBlank()) Text(message, color = LocalVan.current.blue)
        Button(onClick = { build(false) }, modifier = Modifier.fillMaxWidth().testTag("save-routine")) { Text("保存当前作息") }
        OutlinedButton(onClick = { build(true) }, modifier = Modifier.fillMaxWidth()) { Text("保存为另一套作息") }
    }
    sections.firstOrNull { it.number == editing }?.let { section ->
        SectionTimeDialog(section, sections, { editing = 0 }) { replacement ->
            change(sections.map { if (it.number == editing) replacement else it })
            editing = 0
        }
    }
}

@Composable
private fun SectionTimeDialog(section: Section, sections: List<Section>, onDismiss: () -> Unit, onSave: (Section) -> Unit) {
    val first = LocalTime.parse(section.start)
    val last = LocalTime.parse(section.end)
    var startHour by rememberSaveable(section.number) { mutableIntStateOf(first.hour) }
    var startMinute by rememberSaveable(section.number) { mutableIntStateOf(first.minute) }
    var endHour by rememberSaveable(section.number) { mutableIntStateOf(last.hour) }
    var endMinute by rememberSaveable(section.number) { mutableIntStateOf(last.minute) }
    val start = LocalTime.of(startHour, startMinute)
    val end = LocalTime.of(endHour, endMinute)
    val previous = sections.getOrNull(section.number - 2)?.end?.let(LocalTime::parse)
    val next = sections.getOrNull(section.number)?.start?.let(LocalTime::parse)
    val error = when {
        start >= end -> "结束时间需晚于开始时间"
        previous != null && start < previous -> "开始时间需在上一节结束（$previous）之后"
        next != null && end > next -> "结束时间需在下一节开始（$next）之前"
        else -> null
    }
    AlertDialog(containerColor = LocalVan.current.surface, onDismissRequest = onDismiss, title = { Text("第${section.number}节时间设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) { Text("开始时"); NumberWheel(startHour, 0, 23, "开始小时", "routine-start-hour") { startHour = it } }
                    Column(Modifier.weight(1f)) { Text("分"); NumberWheel(startMinute, 0, 59, "开始分钟", "routine-start-minute") { startMinute = it } }
                    Column(Modifier.weight(1f)) { Text("结束时"); NumberWheel(endHour, 0, 23, "结束小时", "routine-end-hour") { endHour = it } }
                    Column(Modifier.weight(1f)) { Text("分"); NumberWheel(endMinute, 0, 59, "结束分钟", "routine-end-minute") { endMinute = it } }
                }
                Text("$start – $end")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onSave(section.copy(start = start.toString(), end = end.toString())) }, modifier = Modifier.testTag("save-section-time")) { Text("确定时间") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
