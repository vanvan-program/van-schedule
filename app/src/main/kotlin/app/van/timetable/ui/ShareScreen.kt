// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.CourseEngine
import app.van.timetable.domain.Schedule
import app.van.timetable.system.Files
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 分享单张课表：数据文件或某一周的图片。从 VanApp 迁出，界面不再直接持有上下文逻辑。 */
@Composable
fun ShareScreen(s: Schedule, vm: VanViewModel) {
    val context = LocalContext.current
    val dark = LocalVan.current.dark
    var week by rememberSaveable {
        mutableStateOf(CourseEngine.week(s, LocalDate.now()).coerceIn(1, s.maxWeeks).toString())
    }
    var teachers by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Action("分享课表数据文件", "对方使用van课表可完整导入") {
            vm.work { withContext(Dispatchers.Main) { Files.shareData(context, s) } }
        }
        Field(week, { week = it }, "分享图片的周次")
        Toggle("图片显示老师", teachers, { teachers = it })
        Button(
            onClick = {
                val w = week.toIntOrNull()
                if (w == null || w !in 1..s.maxWeeks) error = "周次超出学期"
                else
                    vm.work {
                        val bytes = Files.image(s, w, dark, teachers)
                        withContext(Dispatchers.Main) {
                            Files.share(context, "van课表-第${w}周.png", bytes, "image/png")
                        }
                    }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("分享这一周的课表图片")
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Muted("图片包含课程名称、日期、时间和地点，不包含私人备注。")
    }
}