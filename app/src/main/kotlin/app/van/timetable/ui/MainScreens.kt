// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页三个标签里的两个列表型页面：未启用课表时的引导页，以及「课程」总览。
 *
 * 课表标签本身（周视图 / 日列表）在 `ui/timetable/`；这个文件只留不依赖分页与拖拽的部分。
 */

@Composable
fun Welcome(vm: VanViewModel, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Text("van课表", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("每一天，都有清楚的安排。", fontSize = 20.sp)
        Muted("课程保存在本机 · 无广告 · 开源")
        Spacer(Modifier.height(12.dp))
        Action("从 WakeUp 迁移", "备份和 CSV 本地导入") { nav("import") }
        Action("从教务系统导入", "输入教务系统地址，在学校网页中获取课程") { nav("school") }
        Action("手动创建课表", "设置学期、作息并添加课程") { nav("newSchedule") }
        OutlinedButton(onClick = { vm.demo() }, modifier = Modifier.fillMaxWidth()) {
            Text("先查看示例课表")
        }
        Muted("示例数据不会替代你的真实课程。可在课表管理中删除。")
    }
}

@Composable
fun Courses(s: Schedule, now: Instant, nav: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyWeek by rememberSaveable { mutableStateOf(false) }
    val today = now.atZone(ZoneId.of(s.zone)).toLocalDate()
    val week = CourseEngine.week(s, today)
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "课程",
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { nav("notes") },
                modifier = Modifier.testTag("all-course-notes-button"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("课程备注", fontSize = 13.sp)
            }
            IconButton(onClick = { nav("edit:new") }) { Icon(Icons.Outlined.Add, "新增课程") }
        }
        Field(query, { query = it }, "搜索课程、老师、地点")
        Toggle("只看本周有课", onlyWeek, { onlyWeek = it })
        val groups = remember(s) { s.courseGroups() }
        val filtered =
            remember(groups, s, query, onlyWeek, week, currentMonday) {
                groups
                    .filter { group ->
                        val ids = group.members.map { it.id }.toSet()
                        val sessions = s.sessions.filter { it.courseId in ids }
                        (!onlyWeek || sessions.any { n ->
                            n.date?.let { runCatching { LocalDate.parse(it) in currentMonday..currentMonday.plusDays(6) }.getOrDefault(false) }
                                ?: (week in n.weeks)
                        }) && (group.members.any { it.name.contains(query, true) || it.note.contains(query, true) } ||
                            sessions.any { it.teacher.contains(query, true) || it.place.contains(query, true) })
                    }
                    .sortedWith { a, b -> NaturalCourseSort.compare(a.primary.name, b.primary.name) }
            }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (filtered.isEmpty()) item { Muted("没有符合条件的课程") }
            items(filtered, key = { it.primary.id }) { group ->
                val c = group.primary
                Panel(Modifier.testTag("course-group-${c.id}").clickable { nav("detail:${c.id}") }) {
                    Row {
                        Box(
                            Modifier.size(12.dp)
                                .background(
                                    LocalVan.current.courseColor(c),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                    }
                    if (group.members.size > 1) Muted(group.members.map { it.groupSectionLabel() }.distinct().joinToString(" · "))
                    s.sessions
                        .filter { n -> group.members.any { it.id == n.courseId } }
                        .forEach { n ->
                            if (n.date == null) Muted("周次：${formatWeeks(n.weeks)}")
                            Muted(
                                "${if(n.date!=null)n.date else if(n.day in 1..7)weekdays[n.day-1]else"整周"} · ${if(n.mode==TimeMode.SECTIONS)"${n.startSection}–${n.endSection}节"else if(n.mode==TimeMode.CLOCK)"${n.start}–${n.end}"else"无固定时间"} · ${n.place}"
                            )
                        }
                }
            }
        }
    }
}