// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.Schedule
import app.van.timetable.ui.LocalVan
import app.van.timetable.ui.nexio.LocalVanBackdrop
import app.van.timetable.ui.nexio.LocalVanMaterialLevel
import app.van.timetable.ui.nexio.MotionText
import com.haooz.softglass.shape.ContinuousRoundedRectangle

/**
 * 课表页顶栏：周次、课表切换、导入、加课与更多菜单。
 *
 * 0.4.0 时这一整块内联在 `Timetable` 里，与分页状态、拖拽状态混在同一个 670 行的 composable。
 * 现在它只依赖「当前周 + 课表列表 + 一组回调」，菜单与周次选择弹窗的开关也由自己持有。
 */
@Composable
internal fun TimetableHeader(
    s: Schedule,
    week: Int,
    schedules: List<Schedule>,
    daily: Boolean,
    onWeekSelected: (Int) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    onToggleMode: () -> Unit,
    onSelectSchedule: (String) -> Unit,
    nav: (String) -> Unit,
) {
    val colors = LocalVan.current
    val backdrop = LocalVanBackdrop.current
    val materialLevel = LocalVanMaterialLevel.current
    var selectWeek by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scheduleDropdown by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { selectWeek = true },
            modifier = Modifier.testTag("week-selector"),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            MotionText(
                "第${week}周 ▾",
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.clip(ContinuousRoundedRectangle(12.dp))
                    .clickable {
                        if (schedules.size > 1) scheduleDropdown = true
                        else nav("schedules")
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MotionText(
                    s.name,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (schedules.size > 1) {
                    Text("▾", fontSize = 11.sp, color = colors.muted)
                }
            }
            if (scheduleDropdown) {
                GlassDropdownPanel(
                    expanded = scheduleDropdown,
                    onDismiss = { scheduleDropdown = false },
                    backdrop = backdrop,
                    materialLevel = materialLevel,
                    isDark = colors.dark,
                    surfaceColor = colors.surface,
                ) {
                    schedules.forEach { item ->
                        val isCurrent = item.id == s.id
                        GlassDropdownItem(
                            label = if (isCurrent) "${item.name} ✓" else item.name,
                            selected = isCurrent,
                            highlightColor = colors.blue,
                        ) {
                            scheduleDropdown = false
                            if (!isCurrent) onSelectSchedule(item.id)
                        }
                    }
                    GlassDropdownItem(
                        label = "管理全部课表…",
                        selected = false,
                        highlightColor = colors.muted,
                    ) {
                        scheduleDropdown = false
                        nav("schedules")
                    }
                }
            }
        }
        IconButton(onClick = { nav("import") }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.FileUpload, "导入课表", Modifier.size(22.dp))
        }
        IconButton(onClick = { nav("edit:new") }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Add, "添加课程", Modifier.size(22.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.MoreHoriz, "课表菜单", Modifier.size(22.dp))
            }
            if (menu) {
                GlassDropdownPanel(
                    expanded = menu,
                    onDismiss = { menu = false },
                    backdrop = backdrop,
                    materialLevel = materialLevel,
                    isDark = colors.dark,
                    surfaceColor = colors.surface,
                    alignment = Alignment.TopEnd,
                ) {
                    GlassDropdownItem(label = "上一周", enabled = week > 1) {
                        onPrevWeek()
                        menu = false
                    }
                    GlassDropdownItem(label = "下一周", enabled = week < s.maxWeeks) {
                        onNextWeek()
                        menu = false
                    }
                    GlassDropdownItem(label = "回到本周") {
                        onToday()
                        menu = false
                    }
                    GlassDropdownItem(label = if (daily) "切换为周课表" else "切换为日列表") {
                        onToggleMode()
                        menu = false
                    }
                    GlassDropdownItem(label = "课表管理") {
                        menu = false
                        nav("schedules")
                    }
                    GlassDropdownItem(label = "外观设置") {
                        menu = false
                        nav("appearance")
                    }
                }
            }
        }
    }
    if (selectWeek)
        WeekSelectorDialog(
            week = week,
            maxWeeks = s.maxWeeks,
            onDismiss = { selectWeek = false },
            onSelect = { w ->
                selectWeek = false
                onWeekSelected(w)
            },
        )
}