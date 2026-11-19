// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ImportUpdateScreen(
    imported: Schedule,
    state: VanState,
    vm: VanViewModel,
    back: () -> Unit,
    finished: () -> Unit,
) {
    var selectedId by rememberSaveable(imported.id) { mutableStateOf(state.active?.id ?: state.schedules.firstOrNull()?.id) }
    val old = state.schedules.find { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !busy && state.schedules.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(old?.let { "更新 ${it.name} · ${it.week1Monday} 开学" } ?: "选择要更新的课表",
                    Modifier.weight(1f, fill = false))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.schedules.forEach { schedule ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(schedule.name)
                                Muted("${schedule.week1Monday} 开学 · ${schedule.courses.size} 门课" +
                                    if (schedule.id == state.active?.id) " · 已启用" else "")
                            }
                        },
                        onClick = {
                            selectedId = schedule.id
                            expanded = false
                        },
                    )
                }
            }
        }
        if (old != null) {
            key(old, imported, state.preferences.globalDayOverrides) {
                val plan by produceState<Result<ScheduleUpdate.Plan>?>(null) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { ScheduleUpdate.plan(old, imported, state.preferences.globalDayOverrides) }
                    }
                }
                val analysis = plan
                if (analysis == null) Muted("正在核对课程安排…")
                else analysis.fold(onSuccess = { update ->
                    ImportUpdateReview(update, busy) { preview ->
                        vm.applyImportUpdate(update, preview, finished)
                    }
                }, onFailure = { error ->
                    Text(error.message ?: "无法核对课程安排", color = MaterialTheme.colorScheme.error)
                })
            }
        } else {
            TextButton(onClick = back) { Text("返回导入预览") }
        }
    }
}

@Composable
private fun ImportUpdateReview(
    plan: ScheduleUpdate.Plan,
    busy: Boolean,
    save: (ScheduleUpdate.Preview) -> Unit,
) {
    var choices by remember(plan) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    val kept = choices.filterValues { it }.keys
    var result by remember(kept) { mutableStateOf<Result<ScheduleUpdate.Preview>?>(null) }
    LaunchedEffect(kept) {
        result = withContext(Dispatchers.Default) { runCatching { plan.preview(kept) } }
    }
    val preview = result?.getOrNull()
    var acceptOverlaps by remember(choices) { mutableStateOf(false) }
    var showOverlaps by remember(kept) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val differences = plan.differences
    val unresolved = differences.count { it.issues.isNotEmpty() && it.id !in choices }
    val hasChanges = differences.any { it.id !in kept }
    val overlaps = preview?.newOverlaps.orEmpty()
    val canSave = !busy && hasChanges && unresolved == 0 && preview != null &&
        (overlaps.isEmpty() || acceptOverlaps)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("import-update-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Panel {
                    Text("保存前核对", style = MaterialTheme.typography.titleMedium)
                    Muted("沿用原课表的学期日期、已有节次、假期和整日调课。可对应的备注和单次调课会保留。")
                    if (differences.isEmpty()) Text("导入的课程安排与原课表一致。")
                    else Text(ScheduleUpdate.Change.entries.mapNotNull { change ->
                        differences.count { it.change == change }.takeIf { it > 0 }?.let { "${change.label()} $it 项" }
                    }.joinToString(" · "))
                    preview?.schedule?.let { next ->
                        if (next.maxWeeks > plan.original.maxWeeks) Muted("学期周数将延长至 ${next.maxWeeks} 周。")
                        if (next.routines != plan.original.routines) Muted("将补齐导入课程所需的节次。")
                    }
                }
            }
            items(differences.sortedBy { it.issues.isEmpty() }, key = { it.id }) { diff ->
                UpdateDifference(diff, choices[diff.id], enabled = !busy) { keep ->
                    choices = choices + (diff.id to keep)
                }
            }
            if (overlaps.isNotEmpty()) {
                item {
                    Panel {
                        Text("新增时间冲突 ${overlaps.size} 处", fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showOverlaps = !showOverlaps }) {
                            Text(if (showOverlaps) "收起冲突详情" else "展开冲突详情")
                        }
                        Toggle("确认保留这些时间冲突", acceptOverlaps, { acceptOverlaps = it })
                    }
                }
                if (showOverlaps) items(overlaps) { overlap ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(overlap.first.date.toString())
                        Muted("${overlap.first.course.name} ${overlap.first.start}–${overlap.first.end}")
                        Muted("${overlap.second.course.name} ${overlap.second.start}–${overlap.second.end}")
                    }
                }
            }
            result?.exceptionOrNull()?.let { error ->
                item {
                    Text(error.message ?: "无法生成更新结果", color = MaterialTheme.colorScheme.error)
                    Muted("可以保留相关课程的原安排，或返回调整目标课表的作息后再导入。")
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                unresolved > 0 -> Muted("还有 $unresolved 项需要选择。")
                result == null -> Muted("正在检查时间冲突…")
                overlaps.isNotEmpty() && !acceptOverlaps -> Muted("请先确认新增的时间冲突。")
            }
            Button(onClick = { confirm = true }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text("备份并更新课表")
            }
        }
    }
    if (confirm && canSave && preview != null) {
        Confirm(
            "更新「${plan.original.name}」？",
            "将应用已核对的课程安排。更新前会自动备份，之后可在「备份与恢复」找回覆盖前快照。",
            { confirm = false },
        ) {
            confirm = false
            save(preview)
        }
    }
}

@Composable
private fun UpdateDifference(
    diff: ScheduleUpdate.Difference,
    keep: Boolean?,
    enabled: Boolean,
    select: (Boolean) -> Unit,
) {
    Panel {
        val name = (diff.before.courses + diff.imported.courses).map { it.name }.distinct().joinToString("、")
        Text("${diff.change.label()} · $name", fontWeight = FontWeight.Medium)
        if (diff.before.courses.isNotEmpty()) UpdateTimes("原安排", diff.before.sessions)
        if (diff.imported.courses.isNotEmpty()) UpdateTimes("导入安排", diff.imported.sessions)
        diff.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Column(Modifier.selectableGroup()) {
            val useImport = keep == false || (keep == null && diff.issues.isEmpty())
            UpdateChoice("采用导入安排", useImport, enabled) { select(false) }
            val keepLabel = if (diff.before.courses.isEmpty()) "跳过新增" else "保留原安排"
            UpdateChoice(keepLabel, keep == true, enabled) { select(true) }
        }
    }
}

@Composable
private fun UpdateChoice(label: String, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
private fun UpdateTimes(label: String, sessions: List<CourseSession>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (sessions.isEmpty()) Muted("暂无上课时段")
        sessions.forEach { session ->
            val day = session.date ?: weekdays.getOrNull(session.day - 1).orEmpty()
            val time = when (session.mode) {
                TimeMode.SECTIONS -> "${session.startSection}–${session.endSection} 节"
                TimeMode.CLOCK -> "${session.start}–${session.end}"
                TimeMode.UNTIMED -> "无固定上课时间"
            }
            val weeks = if (session.date == null) formatWeeks(session.weeks) else ""
            Muted(listOf(day, time, weeks, session.place, session.teacher).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }
}

private fun ScheduleUpdate.Change.label() = when (this) {
    ScheduleUpdate.Change.ADDED -> "新增"
    ScheduleUpdate.Change.REMOVED -> "取消"
    ScheduleUpdate.Change.TIME -> "时间变更"
    ScheduleUpdate.Change.DETAILS -> "课程信息变更"
}
