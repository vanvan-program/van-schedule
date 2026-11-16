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

private data class SlotForm(
    val id: String = newId(),
    val day: String = "1",
    val weeks: String = "1-20",
    val mode: TimeMode = TimeMode.SECTIONS,
    val first: String = "1",
    val last: String = "2",
    val start: String = "08:00",
    val end: String = "09:40",
    val teacher: String = "",
    val place: String = "",
    val date: String = "",
    val priority: Int = 0,
)

private val slotSaver =
    listSaver<SnapshotStateList<SlotForm>, ArrayList<String>>(
        save = { forms ->
            forms.map { f ->
                arrayListOf(
                    f.id,
                    f.day,
                    f.weeks,
                    f.mode.name,
                    f.first,
                    f.last,
                    f.start,
                    f.end,
                    f.teacher,
                    f.place,
                    f.date,
                    f.priority.toString(),
                )
            }
        },
        restore = { values ->
            values
                .map { v ->
                    SlotForm(
                        v[0],
                        v[1],
                        v[2],
                        TimeMode.valueOf(v[3]),
                        v[4],
                        v[5],
                        v[6],
                        v[7],
                        v[8],
                        v[9],
                        v[10],
                        v[11].toInt(),
                    )
                }
                .toMutableStateList()
        },
    )

private fun CourseSession.form() =
    SlotForm(
        id,
        day.toString(),
        weeks.sorted().joinToString("、"),
        mode,
        startSection.toString(),
        endSection.toString(),
        start,
        end,
        teacher,
        place,
        date.orEmpty(),
        priority,
    )

private fun SlotForm.session(courseId: String) =
    CourseSession(
        id,
        courseId,
        day.toInt(),
        if (date.isBlank()) Importers.weeks(weeks) else emptySet(),
        mode,
        first.toIntOrNull() ?: 1,
        last.toIntOrNull() ?: 2,
        start,
        end,
        teacher,
        place,
        priority,
        date.takeIf { it.isNotBlank() },
    )

@Composable
fun CourseEditor(
    s: Schedule,
    existing: Course?,
    vm: VanViewModel,
    back: () -> Unit,
    parent: Course? = null,
    eventKind: String? = null,
) {
    val draftId = rememberSaveable(existing?.id) { existing?.id ?: newId() }
    val original =
        remember(draftId) {
            existing
                ?: Course(
                    id = draftId,
                    name = parent?.let { "${it.name} · $eventKind" }.orEmpty(),
                    color = parent?.color ?: (s.courses.size % CoursePalette.size),
                    customColor = parent?.customColor,
                    kind = eventKind ?: "普通课程",
                    parentCourseId = parent?.id,
                )
        }
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var note by rememberSaveable(original.id) { mutableStateOf(original.note) }
    var credit by rememberSaveable(original.id) { mutableStateOf(original.credit) }
    var lead by
        rememberSaveable(original.id) { mutableStateOf(original.leadMinutes?.toString().orEmpty()) }
    var color by rememberSaveable(original.id) { mutableIntStateOf(original.color) }
    var customColor by rememberSaveable(original.id) { mutableStateOf(original.customColor) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable(original.id) { mutableStateOf(original.kind) }
    val slots =
        rememberSaveable(original.id, saver = slotSaver) {
            s.sessions
                .filter { it.courseId == original.id }
                .map { it.form() }
                .ifEmpty {
                    val today = LocalDate.now(ZoneId.of(s.zone))
                    val teacher =
                        s.sessions.firstOrNull { it.courseId == parent?.id }?.teacher.orEmpty()
                    listOf(
                        if (parent != null)
                            SlotForm(
                                day = today.dayOfWeek.value.toString(),
                                last = minOf(2, s.routines.minOf { it.sections.size }).toString(),
                                weeks = "",
                                date = today.toString(),
                                teacher = teacher,
                                mode =
                                    if (eventKind == "期末考试") TimeMode.CLOCK else TimeMode.SECTIONS,
                            )
                        else SlotForm(weeks = "1-${s.maxWeeks}", last = minOf(2, s.routines.minOf { it.sections.size }).toString())
                    )
                }
                .toMutableStateList()
        }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    BackHandler { discard = true }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (parent != null) Muted("关联课程：${parent.name}。请填写实际日期、时间和地点；实验课也可清空日期后按周次重复。")
        Field(name, { name = it }, "课程名称")
        Field(credit, { credit = it }, "学分（可选）")
        Field(kind, { kind = it }, "课程类型")
        CourseNoteField(note, { note = it })
        Field(lead, { lead = it }, "提前提醒分钟（空白表示跟随全局）")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocalVan.current.tones.forEachIndexed { i, c ->
                Box(
                    Modifier.size(40.dp)
                        .semantics { contentDescription = "课程颜色${i + 1}" }
                        .background(c, RoundedCornerShape(12.dp))
                        .clickable {
                            color = i
                            customColor = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == i && customColor == null)
                        Text("✓", color = androidx.compose.ui.graphics.Color(0xFF142036))
                }
            }
        }
        OutlinedButton(onClick = { showPalette = true }, modifier = Modifier.fillMaxWidth()) {
            Text(customColor?.let { "自定义颜色 #%06X · 打开调色盘".format(it and 0xFFFFFF) } ?: "打开调色盘")
        }
        slots.forEachIndexed { i, slot ->
            key(slot.id) {
                Panel {
                    Text("上课时段 ${i+1}", fontWeight = FontWeight.Medium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TimeMode.entries.forEach { mode ->
                            FilterChip(
                                slot.mode == mode,
                                { slots[i] = slot.copy(mode = mode,
                                    day = if (mode != TimeMode.UNTIMED && slot.day == "0") "1" else slot.day) },
                                label = {
                                    Text(
                                        when (mode) {
                                            TimeMode.SECTIONS -> "按节次"
                                            TimeMode.CLOCK -> "自定义时间"
                                            TimeMode.UNTIMED -> "无固定时间"
                                        }
                                    )
                                },
                            )
                        }
                    }
                    CalendarDateField("仅一次的日期（可选）", slot.date,
                        { slots[i] = slot.copy(date = it,
                            day = if (it.isNotBlank()) LocalDate.parse(it).dayOfWeek.value.toString() else slot.day,
                            weeks = if (it.isBlank() && slot.weeks.isBlank()) "1-${s.maxWeeks}" else slot.weeks) }, allowClear = true)
                    if (slot.date.isBlank()) WeekSelectionField(slot.weeks, s.maxWeeks) {
                        slots[i] = slot.copy(weeks = it)
                    }
                    if (slot.date.isBlank() || slot.mode == TimeMode.SECTIONS) CourseTimeField(
                        slot.day.toIntOrNull() ?: 1, slot.first.toIntOrNull() ?: 1,
                        slot.last.toIntOrNull() ?: 2, s.routines.minOf { it.sections.size },
                        slot.mode, slot.date.isNotBlank(),
                    ) { day, first, last ->
                        slots[i] = slot.copy(day = day.toString(), first = first.toString(), last = last.toString())
                    }
                    if (slot.mode == TimeMode.CLOCK) {
                        Field(slot.start, { slots[i] = slot.copy(start = it) }, "开始时间 HH:mm")
                        Field(slot.end, { slots[i] = slot.copy(end = it) }, "结束时间 HH:mm")
                    }
                    Field(slot.teacher, { slots[i] = slot.copy(teacher = it) }, "老师")
                    Field(slot.place, { slots[i] = slot.copy(place = it) }, "地点")
                    if (slots.size > 1)
                        TextButton(onClick = { slots.removeAt(i) }) { Text("移除此时段") }
                }
            }
        }
        OutlinedButton(
            onClick = { slots += SlotForm(weeks = "1-${s.maxWeeks}", last = minOf(2, s.routines.minOf { it.sections.size }).toString()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加上课时段")
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        require(name.isNotBlank()) { "课程名称不能为空，请填写名称" }
                        require(lead.isBlank() || lead.toIntOrNull() != null) { "提前分钟必须是整数" }
                        val c =
                            original.copy(
                                name = name.trim(),
                                note = note,
                                credit = credit,
                                color = color,
                                customColor = customColor,
                                kind = kind,
                                leadMinutes = lead.toIntOrNull(),
                            )
                        val values = slots.map { it.session(c.id) }
                        val candidate =
                            s.copy(
                                courses = s.courses.filterNot { it.id == c.id } + c,
                                sessions = s.sessions.filterNot { it.courseId == c.id } + values,
                                sessionOverrides =
                                    s.sessionOverrides.filter { o ->
                                        o.sessionId in
                                            (s.sessions.filterNot { it.courseId == c.id } + values)
                                                .map { it.id }
                                    },
                            )
                        Validation.requireValid(candidate)
                        vm.save(candidate, after = back)
                    }
                    .onFailure { error = it.message ?: "请检查填写内容" }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存课程")
        }
    }
    if (discard) Confirm("离开编辑", "未保存的内容将丢弃。", { discard = false }, back)
    if (showPalette)
        CourseColorPicker(
            customColor ?: CoursePalette.light[color],
            { showPalette = false },
            {
                customColor = it
                showPalette = false
            },
        )
}
