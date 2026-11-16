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
fun Mine(state: VanState, nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Panel {
            Text("van课表", fontWeight = FontWeight.Bold)
            Muted(state.active?.let { "${it.name} · ${it.courses.size}门课程" } ?: "尚未启用课表")
            Muted("本地优先 · 无广告 · GPL-3.0")
        }
        listOf(
                "schedules" to "课表管理",
                "semester" to "学期设置",
                "routines" to "作息管理",
                "holidays" to "节假日设置",
                "days" to "调课管理",
                "appearance" to "外观设置",
                "notifications" to "提醒与小米手环",
                "band" to "手环课表",
                "widgets" to "桌面小组件",
                "backup" to "备份与分享",
                "version" to "关于版本",
                "about" to "帮助与关于",
            )
            .forEach { (r, t) -> Action(t) { nav(r) } }
    }
}
