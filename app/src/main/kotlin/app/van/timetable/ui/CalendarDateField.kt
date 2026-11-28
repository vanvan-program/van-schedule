// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.*

private fun String.utcDateMillis(): Long? =
    runCatching { LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

private fun Long.dateText(): String = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDateField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onlyMondays: Boolean = false,
    allowClear: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DateRange, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(date?.let { "$it ${weekdays[it.dayOfWeek.value - 1]}" } ?: "选择日期")
        }
        if (allowClear && value.isNotBlank()) {
            TextButton(onClick = { onChange("") }, modifier = Modifier.testTag("clear-once-date")) {
                Text("清除日期，按星期和周次上课")
            }
        }
    }
    if (open) {
        val selectable =
            remember(onlyMondays) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !onlyMondays ||
                            Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).dayOfWeek ==
                                DayOfWeek.MONDAY
                }
            }
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = value.utcDateMillis(),
                selectableDates = selectable,
            )
        // Calendar taps (including a tap on the current value) apply to the form draft immediately.
        val applySelection by rememberUpdatedState<(Long) -> Unit> { millis ->
            onChange(millis.dateText())
            open = false
        }
        val tapPicker = remember(picker) {
            object : DatePickerState by picker {
                override var selectedDateMillis: Long?
                    get() = picker.selectedDateMillis
                    set(value) {
                        picker.selectedDateMillis = value
                        value?.let(applySelection)
                    }
            }
        }
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        ) {
            DatePicker(
                state = tapPicker,
                showModeToggle = false,
                title = { Text(if (onlyMondays) "请选择第1周的周一" else label, Modifier.padding(24.dp)) },
                modifier = Modifier.testTag("calendar-picker"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HolidayDateRangeField(from: String, through: String, onChange: (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Text("放假起止日期", style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("holiday-date-range")) {
        Icon(Icons.Outlined.DateRange, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (from == through) from else "$from 至 $through")
    }
    if (!open) return

    val startMillis = from.utcDateMillis()
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = startMillis,
        initialSelectedEndDateMillis = through.utcDateMillis()?.takeIf { startMillis != null && it >= startMillis },
    )
    val start = picker.selectedStartDateMillis?.dateText()
    val end = picker.selectedEndDateMillis?.dateText()
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(enabled = start != null, onClick = {
                if (start != null) onChange(start, end ?: start)
                open = false
            }) { Text(if (start != null && end == null) "仅选这一天" else "确定范围") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    ) {
        DateRangePicker(
            state = picker,
            modifier = Modifier.weight(1f, fill = false).testTag("holiday-range-calendar"),
            title = { Text("放假起止日期", Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = {
                Text("${start ?: "开始日期"} 至 ${end ?: "结束日期"}",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            },
            showModeToggle = false,
        )
    }
}
