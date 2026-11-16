// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.Preferences
import app.van.timetable.ui.VanTheme

class LicenseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VanApplication
        setContent {
            var prefs by remember { mutableStateOf(Preferences()) }
            LaunchedEffect(Unit) { app.store.preferences.collect { prefs = it } }
            VanTheme(prefs) {
                var selected by remember { mutableStateOf("GPL-3.0.txt") }
                val license =
                    remember(selected) {
                        assets.open("licenses/$selected").bufferedReader().use { it.readText() }
                    }
                Column(
                    Modifier.fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    TextButton(onClick = { finish() }) { Text("返回") }
                    Text("van课表 (GPL-3.0-only) · 含 NexioSchedule (AGPL-3.0) 与 softglass (Apache-2.0) 移植组件")
                    Text(
                        "Kotlin、kotlinx、AndroidX、Compose、Material、softglass、Tesseract4Android及中文英文识别模型：Apache-2.0。JExcelAPI：LGPL-2.1。部分视觉动效、渐进模糊、滑选菜单、中文自然排序与小组件莫奈取色参考/移植自 NexioSchedule（AGPL-3.0）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { selected = "GPL-3.0.txt" }) { Text("GPL-3.0") }
                        TextButton(onClick = { selected = "AGPL-3.0.txt" }) { Text("AGPL-3.0") }
                        TextButton(onClick = { selected = "Apache-2.0.txt" }) { Text("Apache-2.0") }
                        TextButton(onClick = { selected = "LGPL-2.1.txt" }) { Text("LGPL-2.1") }
                        TextButton(onClick = { selected = "tesseract-native-notices.txt" }) {
                            Text("原生识别库")
                        }
                    }
                    Text(license, Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}
