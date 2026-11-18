// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import app.van.timetable.ui.nexio.MotionText
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import java.time.*

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = ContinuousRoundedRectangle(20.dp),
        color = LocalVan.current.surface,
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Muted(text: String) {
    MotionText(text, color = LocalVan.current.muted, fontSize = 13.sp)
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = !multi,
        shape = ContinuousRoundedRectangle(14.dp),
    )
}

@Composable
fun Toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit, description: String = "", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (description.isNotEmpty()) Muted(description)
        }
        Switch(value, onChange)
    }
}

@Composable
fun Action(title: String, description: String = "", onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = ContinuousRoundedRectangle(16.dp),
        color = LocalVan.current.surface,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (description.isNotEmpty()) Muted(description)
            }
            Text("›", fontSize = 24.sp, color = LocalVan.current.blue)
        }
    }
}

@Composable
fun Confirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 设置页里的一行：主标题 + 次要说明 + 右侧箭头。 */
data class SettingRow(val route: String, val title: String, val description: String = "")

/**
 * 分组式设置卡片：组标题在外面，组内所有行共享一张圆角卡，行间用一条极淡的分隔线。
 *
 * 之前「我的」页是 12 个独立卡片直接平铺，扫读时找不到分组；改成 iOS / Nexio 那种分组列表。
 */
@Composable
fun SettingsGroup(title: String, rows: List<SettingRow>, onOpen: (String) -> Unit) {
    val c = LocalVan.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = c.muted,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
        Surface(
            Modifier.fillMaxWidth(),
            shape = ContinuousRoundedRectangle(18.dp),
            color = c.surface,
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onOpen(row.route) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, fontSize = 15.sp, lineHeight = 20.sp)
                            if (row.description.isNotEmpty()) {
                                Text(
                                    row.description,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = c.muted,
                                )
                            }
                        }
                        Text("›", fontSize = 20.sp, color = c.muted.copy(alpha = 0.7f))
                    }
                    if (index != rows.lastIndex) {
                        HorizontalDivider(
                            Modifier.padding(start = 16.dp),
                            thickness = 0.6.dp,
                            color = c.muted.copy(alpha = 0.16f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CourseRow(
    o: Occurrence,
    now: Instant,
    onClick: () -> Unit,
    zone: ZoneId = ZoneId.of("Asia/Shanghai"),
    conflict: Boolean = false,
    inactive: Boolean = false,
    pastel: Boolean = true,
) {
    val c = LocalVan.current
    val current = !inactive && now >= o.startsAt(zone) && now < o.endsAt(zone)
    val courseColor = c.courseColor(o.course)
    // 与周课表同一套：浅色底 + 同色系文字，放假/非本周用灰色
    val surface =
        when {
            inactive -> c.inactive
            pastel -> courseColor.copy(alpha = PastelFillAlpha).compositeOver(c.background)
            else -> c.surface
        }
    val accent =
        if (inactive) c.inactiveText
        else if (pastel) {
            Color(
                CoursePalette.pastelTextArgb(
                    courseColor.toArgb().toLong() and 0xFFFFFFFFL,
                    surface.toArgb().toLong() and 0xFFFFFFFFL,
                    c.dark,
                )
            )
        } else courseColor
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = ContinuousRoundedRectangle(if (pastel) 14.dp else 16.dp),
        color = surface,
        contentColor = if (inactive) c.inactiveText else if (pastel) accent else c.text,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.width(4.dp)
                    .height(56.dp)
                    .background(accent, ContinuousRoundedRectangle(3.dp))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(
                        (if (inactive) "[放假]" else "") +
                            (if (o.adjusted) "【调】" else "") +
                            o.course.name,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) Text("正在上课", color = if (pastel) accent else c.blue, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Text(
                    o.place.ifBlank { "地点未填写" }.let { if (pastel) "@$it" else it },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (pastel) accent.copy(alpha = 0.9f) else c.muted,
                )
                Text("${o.start}–${o.end}" + (if (conflict) " · 时间冲突" else ""), fontSize = 12.sp, lineHeight = 16.sp)
                if (o.session.teacher.isNotBlank()) {
                    Text(
                        o.session.teacher,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (pastel) accent.copy(alpha = 0.85f) else c.muted,
                    )
                }
            }
        }
    }
}

fun mondayNow(): LocalDate =
    LocalDate.now(ZoneId.of("Asia/Shanghai"))
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
