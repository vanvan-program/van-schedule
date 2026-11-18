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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 课表页顶栏：课表切换、周次、导入、加课与更多菜单。
 *
 * 0.4.0 时这一整块内联在 `Timetable` 里，与分页状态、拖拽状态混在同一个 670 行的 composable。
 * 现在它只依赖「当前周 + 课表列表 + 一组回调」，菜单与周次选择弹窗的开关也由自己持有。
 *
 * 0.5.7 起「切换课表」不再收在「更多」菜单里，而是以当前课表名的药丸贴在「第N周」左边：
 * 它是这一页最常改的上下文（周次属于哪份课表），藏在两层菜单里既难找也看不出当前是哪一份。
 * 周次左右各留一个等宽权重块，所以药丸出现与否都不会让「第N周」偏离原来的居中位置。
 */
@Composable
internal fun TimetableHeader(
    s: Schedule,
    week: Int,
    schedules: List<Schedule>,
    daily: Boolean,
    showReturn: Boolean,
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
    // 周次在剩余空间居中，操作区独立占位，避免窄屏或大字体时相互覆盖。
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧与周次右侧的空位等宽，周次的中心点固定；课表药丸只在这块空位里伸缩。
        // 0.5.9 起「回到本周」不再是右边缘的竖条，而是这块空位最左边的一枚小药丸：
        // 空位里靠左放它、靠右放课表药丸，两者都只占自己的宽度，周次依旧居中。
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                // 两者都在时一左一右；只有课表药丸时仍旧贴住周次左边（0.5.7 的位置），
                // 只有「回到本周」时靠左，不会因为排布方式变化而乱跑。
                horizontalArrangement = if (showReturn) Arrangement.SpaceBetween else Arrangement.End,
            ) {
                if (showReturn) {
                    ReturnToWeekChip(daily = daily, compact = schedules.size > 1) { onToday() }
                }
                if (schedules.size > 1) {
                    // 内层 Box 只为给下拉面板当锚点：外层占满空位，这里只占药丸本身的宽度。
                    Box {
                        ScheduleSwitcher(
                            name = s.name,
                            onClick = { scheduleDropdown = true },
                        )
                        if (scheduleDropdown) {
                            GlassDropdownPanel(
                                expanded = scheduleDropdown,
                                onDismiss = { scheduleDropdown = false },
                                backdrop = backdrop,
                                materialLevel = materialLevel,
                                isDark = colors.dark,
                                surfaceColor = colors.surface,
                                alignment = Alignment.TopStart,
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
                }
            }
        }
        TextButton(
            onClick = { selectWeek = true },
            modifier = Modifier.testTag("week-selector"),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            MotionText(
                "第${week}周",
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { nav("import") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.FileUpload, "导入课表", Modifier.size(22.dp))
            }
            IconButton(onClick = { nav("edit:new") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Add, "添加课程", Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, "课表菜单", Modifier.size(22.dp))
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

/**
 * 课表切换药丸：当前课表名 + 下拉箭头，贴在「第N周」左边。
 *
 * 名字走单行省略号，宽度由外层权重位决定，所以长课表名不会把周次顶偏，也不会在窄屏上溢出。
 * 点击后弹的还是原来那个课表下拉（含「管理全部课表…」），只是不再藏在「更多」菜单里。
 */
@Composable
private fun ScheduleSwitcher(name: String, onClick: () -> Unit) {
    val colors = LocalVan.current
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .testTag("schedule-switcher")
            .clip(ContinuousRoundedRectangle(12.dp))
            .clickable(onClickLabel = "切换课表", onClick = onClick)
            .semantics { contentDescription = "切换课表，当前 $name" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            name,
            color = colors.muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("▾", color = colors.muted, fontSize = 9.sp)
    }
}
