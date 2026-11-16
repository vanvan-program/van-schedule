// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.Importers
import app.van.timetable.domain.formatWeeks

@Composable
fun WeekSelectionField(value: String, maximum: Int, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("edit-weeks")) {
        Text("周次：${formatWeeks(Importers.weeks(value)).ifBlank { "请选择" }}")
    }
    if (open) WeekSelectionDialog(value, maximum, onDismiss = { open = false }) {
        onChange(it)
        open = false
    }
}

@Composable
private fun WeekSelectionDialog(value: String, maximum: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(Importers.weeks(value).sorted().joinToString(",")) }
    val selected = remember(draft) { Importers.weeks(draft) }
    val latest by rememberUpdatedState(selected)
    val bounds = remember { mutableMapOf<Int, Rect>() }
    var paintSelected = true
    val visited = remember { mutableSetOf<Int>() }
    fun paint(week: Int) {
        if (!visited.add(week)) return
        val current = Importers.weeks(draft)
        draft = (if (paintSelected) current + week else current - week).sorted().joinToString(",")
    }
    AlertDialog(
        containerColor = LocalVan.current.surface,
        onDismissRequest = onDismiss,
        title = { Text("选择周次") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("点击选择，按住并滑动可连续选择或取消。")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("week-multiselect-grid")
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { point ->
                                    visited.clear()
                                    bounds.entries.firstOrNull { it.value.contains(point) }?.key?.let {
                                        paintSelected = it !in latest
                                        paint(it)
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    bounds.entries.firstOrNull { it.value.contains(change.position) }?.key?.let(::paint)
                                },
                                onDragEnd = { visited.clear() },
                                onDragCancel = { visited.clear() },
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items((1..maximum).toList(), key = { it }) { week ->
                        DisposableEffect(week) { onDispose { bounds.remove(week) } }
                        FilterChip(
                            selected = week in selected,
                            colors = vanSelectionColors(),
                            onClick = { draft = (if (week in selected) selected - week else selected + week).sorted().joinToString(",") },
                            label = { Text("$week") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("edit-week-$week")
                                .onGloballyPositioned { bounds[week] = it.boundsInParent() },
                        )
                    }
                }
                Text("已选 ${selected.size} 周")
                if (selected.isEmpty()) Text("至少选择一周后保存。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onSave(draft) }, modifier = Modifier.testTag("save-weeks")) { Text("保存周次") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
