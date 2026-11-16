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
fun Detail(
    s: Schedule,
    c: Course,
    date: String?,
    vm: VanViewModel,
    nav: (String) -> Unit,
    back: () -> Unit,
    sessionId: String? = null,
    originalDate: String? = null,
) {
    var delete by remember { mutableStateOf(false) }
    var deleteRelated by remember { mutableStateOf<Course?>(null) }
    var editNote by rememberSaveable(c.id) { mutableStateOf(false) }
    var noteDraft by rememberSaveable(c.id) { mutableStateOf("") }
    val saving by vm.busy.collectAsStateWithLifecycle()
    var deleteOccurrence by remember { mutableStateOf<Occurrence?>(null) }
    LaunchedEffect(s.id, c.id) { vm.refreshWidgets() }
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions = s.sessions.filter { it.courseId == c.id }
    val members = remember(s, c.id, date) {
        if (date == null) s.courseGroups().firstOrNull { it.primary.id == c.id }?.members ?: listOf(c)
        else listOf(c)
    }
    val occurrences =
        date
            ?.let {
                CourseEngine.resolveDay(s, LocalDate.parse(it)).filter {
                    it.course.id == c.id && (sessionId == null || it.session.id == sessionId) &&
                        (originalDate == null || CourseEngine.originalDate(it) == originalDate)
                }
            }
            .orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val cardColor = LocalVan.current.courseColor(c)
        Surface(
            modifier = Modifier.detailEntrance(),
            color = cardColor, shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    c.name,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = courseTextColor(cardColor),
                )
                Text(
                    "${c.kind}${if(c.credit.isNotBlank())" · ${c.credit}学分"else""}",
                    color = courseTextColor(cardColor),
                )
            }
        }
        Panel {
            Text("课程备注", fontWeight = FontWeight.Medium)
            if (c.note.isBlank()) Muted("记录分数分布、老师是否点名等课程信息。")
            else SelectionContainer { Text(c.note, modifier = Modifier.testTag("course-note-content")) }
            TextButton(
                onClick = { noteDraft = c.note; editNote = true },
                enabled = !saving,
                modifier = Modifier.testTag("edit-course-note"),
            ) { Text(if (c.note.isBlank()) "添加课程备注" else "编辑课程备注") }
        }
        members.forEach { member ->
            val memberSessions = s.sessions.filter { it.courseId == member.id }
            Panel(Modifier.testTag("course-section-${member.id}")) {
                Text(member.groupSectionLabel(), fontWeight = FontWeight.SemiBold, color = LocalVan.current.blue)
                if (member.id != c.id) Text(member.name, fontWeight = FontWeight.Medium)
                if (memberSessions.isEmpty()) Muted("上课时间待定，可编辑课程补充日期或周次。")
                memberSessions.forEachIndexed { index, n ->
                    if (index > 0) HorizontalDivider()
                    Text(n.date ?: if (n.day in 1..7) weekdays[n.day - 1] else "整周实践", fontWeight = FontWeight.Medium)
                    Muted(if (n.mode == TimeMode.SECTIONS) "第${n.startSection}–${n.endSection}节"
                        else if (n.mode == TimeMode.CLOCK) "${n.start}–${n.end}" else "无固定时间")
                    if (n.date == null) Muted("周次：${formatWeeks(n.weeks)}")
                    Muted(listOf(n.teacher, n.place).filter { it.isNotBlank() }.joinToString(" · "))
                }
                if (member.id != c.id) {
                    if (member.note.isNotBlank()) SelectionContainer { Text(member.note) }
                    Toggle("${member.groupSectionLabel()}提醒", member.reminder,
                        { vm.course(s, member.copy(reminder = it), memberSessions, {}) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { nav("edit:${member.id}") }) { Text("编辑${member.groupSectionLabel()}") }
                        TextButton(onClick = { deleteRelated = member }) { Text("删除${member.groupSectionLabel()}", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Panel {
            Toggle(
                "课前提醒",
                c.reminder,
                { vm.course(s, c.copy(reminder = it), sessions, {}) },
                "跟随全局提前量，可在编辑中覆盖",
            )
            if (!state.preferences.reminders) Muted("全局提醒尚未开启，请在我的 → 提醒设置中开启")
        }
        val activeWeek = date?.let { CourseEngine.week(s, LocalDate.parse(it)).takeIf { w -> w in 1..s.maxWeeks } }
        var editWeekNoteFor by remember { mutableStateOf<Int?>(null) }
        var weekNoteDraft by remember { mutableStateOf("") }
        if (activeWeek != null || c.weekNotes.isNotEmpty()) {
            Panel {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("按周备注", fontWeight = FontWeight.Medium)
                    if (activeWeek != null) {
                        TextButton(onClick = {
                            weekNoteDraft = c.weekNotes[activeWeek].orEmpty()
                            editWeekNoteFor = activeWeek
                        }) {
                            Text(if (c.weekNotes[activeWeek].isNullOrBlank()) "添加第${activeWeek}周备注" else "编辑第${activeWeek}周")
                        }
                    }
                }
                if (activeWeek != null && !c.weekNotes[activeWeek].isNullOrBlank()) {
                    Text("第${activeWeek}周：${c.weekNotes[activeWeek]}")
                }
                c.weekNotes.toSortedMap().forEach { (w, text) ->
                    if (w != activeWeek && text.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Muted("第${w}周：$text")
                            TextButton(onClick = {
                                weekNoteDraft = text
                                editWeekNoteFor = w
                            }) { Text("修改") }
                        }
                    }
                }
            }
        }
        editWeekNoteFor?.let { targetWeek ->
            AlertDialog(
                onDismissRequest = { editWeekNoteFor = null },
                title = { Text("第${targetWeek}周课程备注") },
                text = {
                    Field(weekNoteDraft, { weekNoteDraft = it }, "仅在第${targetWeek}周展示", multi = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        val trimmed = weekNoteDraft.trim()
                        val updatedNotes =
                            if (trimmed.isEmpty()) c.weekNotes - targetWeek
                            else c.weekNotes + (targetWeek to trimmed)
                        vm.course(s, c.copy(weekNotes = updatedNotes), sessions, {})
                        editWeekNoteFor = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { editWeekNoteFor = null }) { Text("取消") }
                },
            )
        }
        occurrences.forEach { o ->
            Action("调整 ${o.date} ${o.start} 的课程", "取消、移动或修改这一次课程") {
                nav("adjust:${o.session.id}:${CourseEngine.originalDate(o)}")
            }
            OutlinedButton(
                onClick = { deleteOccurrence = o },
                modifier = Modifier.fillMaxWidth().testTag("delete-occurrence-${o.id}"),
            ) {
                Text("仅删除本日本节", color = MaterialTheme.colorScheme.error)
            }
            Muted("${o.date} ${o.start}–${o.end} · ${o.place}")
            val conflict =
                CourseEngine.conflicts(CourseEngine.resolveDay(s, o.date)).firstOrNull { group ->
                    group.size > 1 && group.any { it.id == o.id }
                }
            if (conflict != null) {
                Panel {
                    Text("同一时段有 ${conflict.size} 门课程")
                    Muted("当前主要显示：${CourseEngine.main(conflict).course.name}")
                    TextButton(
                        onClick = {
                            val priority = (s.sessions.maxOfOrNull { it.priority } ?: 0) + 1
                            vm.save(
                                s.copy(
                                    sessions =
                                        s.sessions.map {
                                            if (it.id == o.session.id) it.copy(priority = priority)
                                            else it
                                        }
                                )
                            )
                        }
                    ) {
                        Text("优先显示并提醒这门课程")
                    }
                    Muted("此设置适用于这个上课时段的全部周次；冲突课程仍保留在日列表。")
                }
            }
        }
        Button(onClick = { nav("edit:${c.id}") }, modifier = Modifier.fillMaxWidth()) {
            Text("编辑整门课程")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { nav("quick:${c.id}:lab") }, modifier = Modifier.weight(1f)) {
                Text("添加实验课")
            }
            OutlinedButton(
                onClick = { nav("quick:${c.id}:exam") },
                modifier = Modifier.weight(1f),
            ) {
                Text("添加期末考试")
            }
        }
        TextButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("删除课程", color = MaterialTheme.colorScheme.error)
        }
    }
    deleteRelated?.let { related ->
        Confirm("删除${related.groupSectionLabel()}", "将删除“${related.name}”的全部时段与单次调整，正课及其他关联课程保留。", { deleteRelated = null }) {
            vm.deleteCourse(s, related.id) { deleteRelated = null }
        }
    }
    if (editNote) CourseNoteDialog(
        value = noteDraft,
        onChange = { noteDraft = it },
        saving = saving,
        onSave = { vm.courseNote(s.id, c.id, noteDraft) { editNote = false } },
        onDismiss = { editNote = false },
    )
    if (delete)
        Confirm("删除课程", "将删除“${c.name}”的全部时段和单次调整，并取消相关提醒。" + if (members.size > 1) "关联的实验课和考试将保留为独立课程。" else "", { delete = false }) {
            vm.deleteCourse(s, c.id, back)
        }
    deleteOccurrence?.let { o ->
        Confirm("仅删除本日本节", "取消“${c.name}”在 ${o.date} ${o.start}–${o.end} 的这一次课程。其他日期和时段保留。", { deleteOccurrence = null }) {
            vm.save(CourseEngine.cancelOccurrence(s, o), after = back)
            deleteOccurrence = null
        }
    }
}
