// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.Manifest
import android.app.AlarmManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import app.van.timetable.system.Scheduler
import app.van.timetable.ui.nexio.WallpaperStore
import app.van.timetable.ui.nexio.toCardSoftGlassStyle
import app.van.timetable.ui.nexio.vanSoftGlassBackdrop
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import kotlin.math.roundToInt

@Composable
fun ScheduleList(state: VanState, vm: VanViewModel, nav: (String) -> Unit) {
    var delete by remember { mutableStateOf<Schedule?>(null) }
    var newSemesterTarget by remember { mutableStateOf<Schedule?>(null) }
    var nextMonday by remember { mutableStateOf(mondayNow().toString()) }
    var nextWeeks by remember { mutableStateOf("20") }
    var nextName by remember { mutableStateOf("") }
    var semesterError by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Button(onClick = { nav("newSchedule") }, modifier = Modifier.fillMaxWidth()) {
            Text("新建课表")
        }
        OutlinedButton(onClick = { nav("import") }, modifier = Modifier.fillMaxWidth()) {
            Text("导入新课表")
        }
        state.schedules.forEachIndexed { idx, s ->
            Panel {
                Text(s.name, fontWeight = FontWeight.Bold)
                Muted("${s.courses.size}门 · ${s.source} · ${s.week1Monday} 起 ${s.maxWeeks}周")
                if (s.id == state.preferences.activeScheduleId)
                    Text("已启用 · 提醒和小组件使用此表", color = LocalVan.current.blue)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.prefs { it.copy(activeScheduleId = s.id) } }) {
                        Text("启用")
                    }
                    TextButton(onClick = { nav("schedule:${s.id}") }) { Text("设置") }
                    if (idx > 0) {
                        TextButton(onClick = { vm.moveSchedule(s.id, -1) }) { Text("上移") }
                    }
                    if (idx < state.schedules.lastIndex) {
                        TextButton(onClick = { vm.moveSchedule(s.id, 1) }) { Text("下移") }
                    }
                    TextButton(onClick = {
                        nextMonday = mondayNow().toString()
                        nextWeeks = s.maxWeeks.toString()
                        nextName = s.name
                        semesterError = ""
                        newSemesterTarget = s
                    }) { Text("开启新学期") }
                    TextButton(onClick = { vm.save(Importers.freshCopy(s, "${s.name}副本")) }) {
                        Text("复制")
                    }
                    TextButton(onClick = { nav("share:${s.id}") }) { Text("分享") }
                    TextButton(onClick = { delete = s }) { Text("删除") }
                }
            }
        }
    }
    delete?.let { s ->
        Confirm("删除课表", "将删除“${s.name}”及其课程和调课。如果它已启用，提醒和小组件将停止展示其课程。", { delete = null }) {
            vm.delete(s.id)
            delete = null
        }
    }
    newSemesterTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { newSemesterTarget = null },
            title = { Text("开启新学期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted("将复用“${s.name}”的作息与总周数设置，并清空当前课程、假期与调课记录。")
                    Field(nextName, { nextName = it }, "课表名称")
                    CalendarDateField("第1周周一", nextMonday, { nextMonday = it }, onlyMondays = true)
                    Field(nextWeeks, { nextWeeks = it }, "总周数")
                    if (semesterError.isNotEmpty()) Text(semesterError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val weeks = nextWeeks.toInt()
                        val candidate = CourseEngine.startNewSemester(s, nextMonday, weeks, nextName)
                        Validation.requireValid(candidate)
                        vm.startNewSemester(s, nextMonday, weeks, nextName)
                        newSemesterTarget = null
                    }.onFailure { semesterError = it.message.orEmpty() }
                }) { Text("确认开启") }
            },
            dismissButton = { TextButton(onClick = { newSemesterTarget = null }) { Text("取消") } },
        )
    }
}
