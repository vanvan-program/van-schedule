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
fun ScheduleEditor(s: Schedule?, vm: VanViewModel, back: () -> Unit) {
    var name by rememberSaveable(s?.id) { mutableStateOf(s?.name ?: "我的课表") }
    var monday by
        rememberSaveable(s?.id) { mutableStateOf(s?.week1Monday ?: mondayNow().toString()) }
    var weeks by rememberSaveable(s?.id) { mutableStateOf(s?.maxWeeks?.toString() ?: "20") }
    var error by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<Schedule?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Field(name, { name = it }, "课表名称")
        CalendarDateField(
            "第1周周一",
            monday,
            { monday = it },
            Modifier.testTag("semester-start-date"),
            onlyMondays = true,
        )
        Field(weeks, { weeks = it }, "总周数")
        Muted("调整日期会重新计算课程、单次调整和提醒。已有日期调整仍保留具体日期，请检查调课管理。默认作息仅为模板，需要按实际情况修改。")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                runCatching {
                        val v =
                            (s ?: Schedule(week1Monday = monday)).copy(
                                name = name,
                                week1Monday = monday,
                                maxWeeks = weeks.toInt(),
                            )
                        Validation.requireValid(v)
                        candidate = v
                        if (s == null) vm.save(v, true, back) else confirm = true
                    }
                    .onFailure { error = it.message.orEmpty() }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (s == null) "创建并启用" else "保存学期设置")
        }
    }
    if (confirm)
        Confirm(
            "更新学期",
            "将重新计算 ${s?.courses?.size} 门课程。已有课程的周次不变，日期会跟随新的第1周起点变化。",
            { confirm = false },
        ) {
            candidate?.let { vm.save(it, after = back) }
        }
}
