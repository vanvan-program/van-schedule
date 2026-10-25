// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.van.timetable.VanApplication
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun BandScreen(state: VanState) {
    val app = LocalContext.current.applicationContext as VanApplication
    val sync = app.band
    val status by sync.status.collectAsState()
    LifecycleResumeEffect(sync) {
        sync.refreshConnection()
        onPauseOrDispose { }
    }
    var clear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel {
            Text("小米手环 10 Pro", fontWeight = FontWeight.Bold)
            Muted("今天与明天 · 14天离线缓存")
            Text("同步课表：${state.active?.name ?: "尚未启用课表"}", Modifier.testTag("band-schedule"))
            Text(status.connectionMessage, Modifier.testTag("band-connection"))
            TextButton(onClick = { sync.refreshConnection() }, enabled = !status.busy && !status.checking,
                modifier = Modifier.testTag("band-check")) { Text("重新检查连接") }
            Text(status.message, Modifier.testTag("band-status"))
            if (status.busy || status.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.lastSync > 0) Muted("手环最后确认：" + Instant.ofEpochMilli(status.lastSync)
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
            if (status.through.isNotBlank()) Muted("缓存截止：${status.through}")
        }
        Panel {
            Toggle("自动同步到手环", status.enabled, { sync.enable(it) },
                "打开手机课表、修改数据或恢复连接时尝试同步；受系统后台限制时可手动同步。", Modifier.testTag("band-enable"))
            Button(onClick = { sync.sync() }, enabled = !status.busy && state.active != null,
                modifier = Modifier.fillMaxWidth().testTag("band-sync")) { Text("立即同步") }
            OutlinedButton(onClick = { sync.sync(probe = true) }, enabled = !status.busy,
                modifier = Modifier.fillMaxWidth().testTag("band-probe")) { Text("验证通信（示例课程）") }
            TextButton(onClick = { clear = true }, enabled = !status.busy,
                modifier = Modifier.testTag("band-clear")) { Text("清除手环课表") }
        }
        Panel {
            Text("首次连接", fontWeight = FontWeight.Bold)
            Muted("1. 在 Windows AstroBox 中安装同套 van手环 RPK。\n2. 在手环上打开 van手环。\n3. 断开电脑 AstroBox，确认手机小米运动健康已连接手环。\n4. 点击验证通信，在手机完成设备管理授权。\n5. 断连重开手环应用，确认示例仍在，再同步正式课表。")
            Muted("示例只替换手环缓存，不修改手机课表。同步需手环确认保存后才显示成功。")
        }
    }
    if (clear) Confirm("清除手环课表", "清除手环缓存，手机课程保留；同时关闭自动同步，避免马上重新同步。", { clear = false }) {
        clear = false
        sync.enable(false)
        sync.sync(clear = true)
    }
}
