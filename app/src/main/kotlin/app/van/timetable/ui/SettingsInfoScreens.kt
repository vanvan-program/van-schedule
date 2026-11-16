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
fun NotificationSettings(p: Preferences, vm: VanViewModel) {
    val context = LocalContext.current
    val manager = context.getSystemService(AlarmManager::class.java)
    var lead by rememberSaveable(p.leadMinutes) { mutableStateOf(p.leadMinutes.toString()) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh++
            vm.work {}
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Toggle(
                "课前提醒",
                p.reminders,
                { v ->
                    vm.prefs { it.copy(reminders = v) }
                    if (v && Build.VERSION.SDK_INT >= 33)
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Toggle(
                "当前／下一节状态通知",
                p.statusNotification,
                { v -> vm.prefs { it.copy(statusNotification = v) } },
            )
            Field(lead, { lead = it }, "提前分钟数")
            Button(
                onClick = {
                    val value = lead.toIntOrNull()
                    if (value == null || value !in 0..1440) error = "填写0–1440之间的整数"
                    else vm.prefs { it.copy(leadMinutes = value) }
                }
            ) {
                Text("保存提前量")
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        Panel {
            val notificationAllowed =
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            Text("通知：${if(notificationAllowed)"已允许"else"尚未允许"}")
            Text("精确提醒：${if(exact)"已允许"else"普通提醒，可能延迟"}")
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 31)
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    refresh++
                }
            ) {
                Text("打开精确提醒授权")
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                    refresh++
                }
            ) {
                Text("打开系统通知设置")
            }
            TextButton(onClick = { refresh++ }) { Text("重新检查状态 $refresh") }
        }
        Panel {
            Text("小米手环通知镜像", fontWeight = FontWeight.Medium)
            Text(
                "1. 连接小米运动健康与手环。\n2. 在消息通知中允许van课表。\n3. 点击下面的测试按钮，检查手机和手环是否都收到。\n4. 未收到时检查通知授权、运动健康连接和手机省电设置。"
            )
            Button(onClick = { Scheduler.testNotification(context) }) { Text("发送测试通知") }
            Muted("是否转发取决于运动健康设置和实际设备，不使用额外蓝牙权限。")
        }
    }
}

@Composable
fun About(nav: (String) -> Unit = {}) {
    val context = LocalContext.current
    val version =
        remember(context) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Text("van课表 $version", style = MaterialTheme.typography.headlineSmall)
            Text("GPL-3.0-only · 版本信息与更新检查")
            Muted("不同教务系统、实际导出文件与国产系统通知仍需要验证。")
        }
        Panel {
            Text("隐私说明", fontWeight = FontWeight.Medium)
            Text(
                "课程与设置保存在本机，不收集使用统计。教务登录内容直接进入学校网站，App不读取或保存账号密码。默认导入完成清除登录会话；可主动保留。导出文件包含你选择的课程信息，请自行决定分享对象。备份不包含登录Cookie和系统权限。"
            )
            Text(
                "网络仅用于访问学校教务，以及你点击“检查更新”时读取GitHub发布页的版本号与更新说明；检查过程不上传课表、账号或设备标识。没有广告、内购、分析SDK或自建服务器。"
            )
        }
        Panel {
            Text("帮助", fontWeight = FontWeight.Medium)
            Text(
                "PDF、Excel和课表图片先核对识别结果；缺少的星期、节次或周次需补全。图片和PDF在本机识别，请使用包含表头的清晰完整课表。CSV、Excel和图片通常不携带作息与开学日期，保存前必须核对。教务获取失败时进入所选学期的个人课表页后手动获取。已结束课程只在小组件隐藏，App日列表仍可查。后台刷新和提醒受系统授权及省电策略影响。"
            )
            Text("完整恢复会替换全部课表，操作前自动保存本机恢复快照。")
        }
        Action("版本与更新", "当前 $version · 检查是否为最新版本") { nav("version") }
        Action("历史版本更新", "查看 $version 及以往版本的更新内容") { nav("changelog") }
        Action("开源与第三方许可", "查看本地许可声明") {
            context.startActivity(Intent(context, app.van.timetable.LicenseActivity::class.java))
        }
    }
}