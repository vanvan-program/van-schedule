// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.Locale

private fun parseClock(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

@Composable
internal fun ClockRangeField(
    start: String,
    end: String,
    onChange: (String, String) -> Unit,
    optional: Boolean = false,
    suggestedStart: String = "08:00",
    suggestedEnd: String = "08:45",
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = if (optional) "调整上课时间" else "自定义上课时间"
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Schedule, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (optional && start.isBlank() && end.isBlank()) "沿用课程原时间" else "$start – $end")
    }
    if (!open) return

    var from by rememberSaveable { mutableStateOf(start.ifBlank { suggestedStart }) }
    var through by rememberSaveable { mutableStateOf(end.ifBlank { suggestedEnd }) }
    var editingEnd by rememberSaveable { mutableStateOf(false) }
    val first = parseClock(from)
    val last = parseClock(through)
    var typing by rememberSaveable { mutableStateOf(first == null || last == null) }
    val valid = first != null && last != null && first < last
    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = LocalVan.current.surface,
        title = { Text(label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (typing) {
                    Field(from, { from = it }, "开始时间 HH:mm")
                    Field(through, { through = it }, "结束时间 HH:mm")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!editingEnd, { editingEnd = false }, label = { Text("开始 $from") })
                        FilterChip(editingEnd, { editingEnd = true }, label = { Text("结束 $through") })
                    }
                    val time = (if (editingEnd) last else first) ?: LocalTime.MIDNIGHT
                    fun change(hour: Int, minute: Int) {
                        val text = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                        if (editingEnd) through = text else from = text
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("时")
                            NumberWheel(time.hour, 0, 23, "小时", "clock-hour") { change(it, time.minute) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("分")
                            NumberWheel(time.minute, 0, 59, "分钟", "clock-minute") { change(time.hour, it) }
                        }
                    }
                }
                if (!valid) Text(
                    if (first == null || last == null) "请输入有效时间，如 08:30" else "结束时间须晚于开始时间",
                    color = MaterialTheme.colorScheme.error,
                )
                Row {
                    TextButton(onClick = { typing = !typing }, enabled = !typing || (first != null && last != null)) {
                        Text(if (typing) "滚轮选择" else "键盘输入")
                    }
                    if (optional) TextButton(onClick = { onChange("", ""); open = false }) { Text("沿用原时间") }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onChange(from, through); open = false }) { Text("确定时间") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}
