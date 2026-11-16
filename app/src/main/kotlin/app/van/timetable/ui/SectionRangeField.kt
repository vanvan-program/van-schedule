// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.van.timetable.domain.TimeMode

/** Day and section wheels edit a temporary draft. Dismissal never changes the course. */
@Composable
fun CourseTimeField(day: Int, first: Int, last: Int, maximum: Int, mode: TimeMode, once: Boolean,
                    onChange: (Int, Int, Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var weekday by rememberSaveable { mutableIntStateOf(day) }
    var start by rememberSaveable { mutableIntStateOf(first.coerceIn(1, maximum)) }
    var end by rememberSaveable { mutableIntStateOf(last.coerceIn(start, maximum)) }
    OutlinedButton(onClick = {
        weekday = day.coerceIn(if (mode == TimeMode.UNTIMED) 0 else 1, 7)
        start = first.coerceIn(1, maximum)
        end = last.coerceIn(start, maximum)
        open = true
    }, modifier = Modifier.fillMaxWidth().testTag("course-time")) {
        Text(listOfNotNull(
            if (once) null else if (day in 1..7) weekdays[day - 1] else "整周实践",
            if (mode == TimeMode.SECTIONS) "第${first}–${last}节" else null,
        ).joinToString(" · ").ifBlank { "按指定日期上课" })
    }
    if (open) AlertDialog(
        containerColor = LocalVan.current.surface,
        onDismissRequest = { open = false },
        title = { Text("选择上课时间") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!once) Column(Modifier.weight(1f)) {
                    Text("星期")
                    NumberWheel(weekday, if (mode == TimeMode.UNTIMED) 0 else 1, 7,
                        "星期滚轮", "weekday-wheel", { if (it == 0) "整周" else weekdays[it - 1] }) { weekday = it }
                }
                if (mode == TimeMode.SECTIONS) {
                    Column(Modifier.weight(1f)) {
                        Text("开始节")
                        NumberWheel(start, 1, maximum, "开始节滚轮", "start-section-wheel", { "第${it}节" }) {
                            start = it
                            if (end < it) end = it
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("结束节")
                        NumberWheel(end, start, maximum, "结束节滚轮", "end-section-wheel", { "第${it}节" }) { end = it }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChange(weekday, start, end); open = false }) { Text("保存上课时间") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}

@Composable
fun NumberWheel(value: Int, minimum: Int, maximum: Int, label: String, tag: String,
                format: (Int) -> String = { "%02d".format(it) }, onChange: (Int) -> Unit) {
    val callback by rememberUpdatedState(onChange)
    val textColor = MaterialTheme.colorScheme.onSurface
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(160.dp).testTag(tag),
        factory = { context -> NumberPicker(context).apply {
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, number -> callback(number) }
        } },
        update = { picker ->
            val labels = (minimum..maximum).map(format).toTypedArray()
            if (picker.minValue != minimum || picker.maxValue != maximum || !picker.displayedValues.contentEquals(labels)) {
                picker.displayedValues = null
                picker.minValue = 0
                picker.maxValue = maximum
                picker.minValue = minimum
                picker.displayedValues = labels
            }
            picker.value = value.coerceIn(minimum, maximum)
            picker.contentDescription = label
            if (android.os.Build.VERSION.SDK_INT >= 29) picker.textColor = android.graphics.Color.argb(
                255, (textColor.red * 255).toInt(), (textColor.green * 255).toInt(), (textColor.blue * 255).toInt())
        },
    )
}
