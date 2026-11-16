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

@Composable
fun Appearance(p: Preferences, vm: VanViewModel, schedule: Schedule?) {
    val canCollapseGaps = remember(schedule) { schedule?.let(GridTimeline::canCollapseGaps) ?: false }
    val context = LocalContext.current
    val colors = LocalVan.current
    val previewBackdrop = rememberLayerBackdrop()
    var wallpaperBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(p.wallpaperUri) {
        wallpaperBitmap = WallpaperStore.loadImageBitmap(p.wallpaperUri, 1280)
    }
    val pickWallpaper =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.work {
                    val savedPath = WallpaperStore.saveFromUri(context, uri)
                    vm.store.updatePreferences { it.copy(wallpaperUri = savedPath) }
                }
            }
        }
    val materialSupported = remember { isRenderEffectSupported() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val previewShape = ContinuousRoundedRectangle(18.dp)
        Box(
            Modifier.fillMaxWidth()
                .height(150.dp)
                .clip(previewShape),
        ) {
            Box(Modifier.fillMaxSize().layerBackdrop(previewBackdrop)) {
                if (wallpaperBitmap != null) {
                    val gain = p.wallpaperBrightness.coerceIn(0.3f, 1.4f)
                    Image(
                        bitmap = wallpaperBitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(
                            ColorMatrix(
                                floatArrayOf(
                                    gain, 0f, 0f, 0f, 0f,
                                    0f, gain, 0f, 0f, 0f,
                                    0f, 0f, gain, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f,
                                )
                            )
                        ),
                        modifier =
                            Modifier.fillMaxSize().let { m ->
                                if (p.wallpaperBlurDp > 0.5f && materialSupported) {
                                    m.blur(p.wallpaperBlurDp.dp)
                                } else m
                            },
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(colors.top))
                }
            }
            val sampleCourseColor = colors.tones.firstOrNull() ?: colors.blue
            val cardShape = ContinuousRoundedRectangle(p.cardCornerDp.coerceIn(0f, 28f).dp)
            val cardStyle = remember(p, colors.dark, sampleCourseColor) {
                p.toCardSoftGlassStyle(colors.dark, sampleCourseColor)
            }
            val glassOn = p.materialLevel != "none" && materialSupported
            val cardModifier =
                if (glassOn) {
                    Modifier.vanSoftGlassBackdrop(
                        backdrop = previewBackdrop,
                        shape = cardShape,
                        style = cardStyle,
                        radiusPx = p.cardCornerDp * 2.5f,
                        keySuffix = "AppearancePreview",
                    )
                } else {
                    Modifier.background(
                        sampleCourseColor.copy(alpha = if (p.materialLevel == "none") 1f else p.cardOpacity),
                        cardShape,
                    )
                }
            val contentAlignment =
                when (p.cardAlignment) {
                    "top" -> Alignment.TopCenter
                    "bottom" -> Alignment.BottomCenter
                    else -> Alignment.Center
                }
            val textColor =
                when (p.cardTextColorMode) {
                    "light" -> Color.White
                    "dark" -> Color(0xFF142036)
                    else -> courseTextColor(sampleCourseColor)
                }
            Box(
                Modifier.align(Alignment.Center)
                    .width(120.dp)
                    .height(104.dp)
                    .then(cardModifier)
                    .padding(8.dp),
                contentAlignment = contentAlignment,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "数据结构",
                        color = textColor,
                        fontSize = (12f * p.cardTextScale).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    if (p.showCardPlace) {
                        Text(
                            "示例楼 B302",
                            color = textColor.copy(alpha = 0.85f),
                            fontSize = (9.5f * p.cardTextScale).sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (p.showCardTeacher) {
                        Text(
                            "张老师",
                            color = textColor.copy(alpha = 0.78f),
                            fontSize = (9f * p.cardTextScale).sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Panel {
            Text("主题模式", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (id, label) ->
                    FilterChip(
                        p.theme == id,
                        { vm.prefs { it.copy(theme = id) } },
                        label = { Text(label) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Toggle(
                    "莫奈取色（Android 12+）",
                    p.monetColor,
                    { v -> vm.prefs { it.copy(monetColor = v) } },
                    "跟随系统壁纸动态配色；关闭后恢复课程自定义颜色",
                )
            }
        }

        Panel {
            Text("材质与顶栏模糊", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("soft" to "柔光玻璃", "frosted" to "清透磨砂", "none" to "无材质").forEach { (id, label) ->
                    FilterChip(
                        selected = (if (!materialSupported) "none" else p.materialLevel) == id,
                        enabled = materialSupported || id == "none",
                        onClick = { vm.prefs { it.copy(materialLevel = id) } },
                        label = { Text(label) },
                    )
                }
            }
            if (!materialSupported) Muted("当前系统低于 Android 12，已自动锁定为无材质档")
            Toggle(
                "顶栏「真·渐进模糊」",
                p.trueProgressiveBlur,
                { v -> vm.prefs { it.copy(trueProgressiveBlur = v) } },
                "Android 13+ 使用 AGSL 黄金角螺旋真渐进模糊；关闭后改为轻量模糊淡出",
            )
        }

        Panel {
            Text("壁纸设置", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickWallpaper.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (p.wallpaperUri.isBlank()) "选择壁纸" else "更换壁纸") }
                if (p.wallpaperUri.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            vm.work {
                                WallpaperStore.clearFiles(context)
                                vm.store.updatePreferences { it.copy(wallpaperUri = "") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("清除壁纸") }
                }
            }
            Text("壁纸亮度：${(p.wallpaperBrightness * 100).roundToInt()}%")
            Slider(p.wallpaperBrightness, { v -> vm.prefs { it.copy(wallpaperBrightness = v) } }, valueRange = 0.4f..1.2f)
            Text("壁纸模糊：${p.wallpaperBlurDp.roundToInt()} dp")
            Slider(p.wallpaperBlurDp, { v -> vm.prefs { it.copy(wallpaperBlurDp = v) } }, valueRange = 0f..24f)
        }

        Panel {
            Text("课程卡片效果与排版", fontWeight = FontWeight.Medium)
            Text("卡片着色程度：${(p.cardTintStrength * 100).roundToInt()}%")
            Slider(p.cardTintStrength, { v -> vm.prefs { it.copy(cardTintStrength = v) } }, valueRange = 0f..1f)
            Text("卡片不透明度：${(p.cardOpacity * 100).roundToInt()}%")
            Slider(p.cardOpacity, { v -> vm.prefs { it.copy(cardOpacity = v) } }, valueRange = 0.25f..1f)
            Text("卡片模糊强度：${p.cardBlurDp.roundToInt()} dp")
            Slider(p.cardBlurDp, { v -> vm.prefs { it.copy(cardBlurDp = v) } }, valueRange = 0f..32f)
            Text("卡片折射强度：${(p.cardRefraction * 100).roundToInt()}%")
            Slider(p.cardRefraction, { v -> vm.prefs { it.copy(cardRefraction = v) } }, valueRange = 0f..1.4f)
            Text("卡片圆角（Squircle）：${p.cardCornerDp.roundToInt()} dp")
            Slider(p.cardCornerDp, { v -> vm.prefs { it.copy(cardCornerDp = v) } }, valueRange = 2f..20f)
            Text("内容对齐方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "顶部", "center" to "居中", "bottom" to "底部").forEach { (id, label) ->
                    FilterChip(p.cardAlignment == id, { vm.prefs { it.copy(cardAlignment = id) } }, label = { Text(label) })
                }
            }
            Text("卡片文字颜色")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "自动对比", "dark" to "深色字", "light" to "浅色字").forEach { (id, label) ->
                    FilterChip(p.cardTextColorMode == id, { vm.prefs { it.copy(cardTextColorMode = id) } }, label = { Text(label) })
                }
            }
            Text("卡片文字缩放：${(p.cardTextScale * 100).roundToInt()}%")
            Slider(p.cardTextScale, { v -> vm.prefs { it.copy(cardTextScale = v) } }, valueRange = 0.8f..1.35f)
            Toggle("卡片显示地点", p.showCardPlace, { v -> vm.prefs { it.copy(showCardPlace = v) } })
            Toggle("卡片显示教师", p.showCardTeacher, { v -> vm.prefs { it.copy(showCardTeacher = v) } })
        }

        Panel {
            Text("全局字体与网格尺寸", fontWeight = FontWeight.Medium)
            Text("字体大小")
            Row {
                listOf(0.85f to "小", 1f to "标准", 1.15f to "大", 1.3f to "更大").forEach { (v, t) ->
                    TextButton(onClick = { vm.prefs { it.copy(fontScale = v) } }) {
                        Text(if (p.fontScale == v) "✓ $t" else t)
                    }
                }
            }
            Toggle(
                "自动适配整周",
                p.fitWeek,
                { v -> vm.prefs { it.copy(fitWeek = v) } },
                "根据屏幕高度显示全部节次；大字体或节次较多时仍可上下滑动",
            )
            Text(if (p.fitWeek) "最大节次行高：${p.rowHeight}" else "节次行高：${p.rowHeight}")
            Row {
                listOf(48, 56, 68, 80).forEach { v ->
                    TextButton(onClick = { vm.prefs { it.copy(rowHeight = v) } }) { Text("$v") }
                }
            }
        }
        Panel {
            Text("视图行为与动效", fontWeight = FontWeight.Medium)
            Toggle("显示周末", p.showWeekend, { v -> vm.prefs { it.copy(showWeekend = v) } })
            Toggle(
                "显示周次进度条",
                p.showWeekProgress,
                { v -> vm.prefs { it.copy(showWeekProgress = v) } },
                "在课表底部拖动切换周次；关闭后仍可左右滑动或点击顶部周次切换",
                modifier = Modifier.testTag("week-progress-setting"),
            )
            Toggle("显示非本周课程", p.showInactive, { v -> vm.prefs { it.copy(showInactive = v) } })
            Toggle(
                "周末无课时自动跳下一周",
                p.autoSkipEmptyWeekend,
                { v -> vm.prefs { it.copy(autoSkipEmptyWeekend = v) } },
            )
            Toggle(
                "日列表按上午 / 下午 / 晚上分组",
                p.groupDayListByPeriod,
                { v -> vm.prefs { it.copy(groupDayListByPeriod = v) } },
            )
            Toggle(
                "保留课间时间空档",
                p.showCourseGaps ?: !canCollapseGaps,
                { v -> vm.prefs { it.copy(showCourseGaps = v) } },
                "${if (p.showCourseGaps == null) "当前自动判断。" else "当前使用手动设置。"}课程均在作息节次内时默认收起课间空档，作息外有课时保留；课程卡片始终保留圆角和小间距，空课节次仍保留",
                modifier = Modifier.testTag("course-gap-setting"),
            )
            if (p.showCourseGaps != null) TextButton(onClick = { vm.prefs { it.copy(showCourseGaps = null) } }) {
                Text("恢复自动判断")
            }
            Toggle("全局文字变化模糊动效", p.textMotionBlur, { v -> vm.prefs { it.copy(textMotionBlur = v) } })
            Toggle("预测性返回动画", p.predictiveBack, { v -> vm.prefs { it.copy(predictiveBack = v) } })
            Toggle(
                "显示自定义时间课时间",
                p.showCustomTimeLabels,
                { v -> vm.prefs { it.copy(showCustomTimeLabels = v) } },
                "在课表左侧显示自定义时间课程的时间；关闭后保留正常节次时间，不影响桌面小组件",
            )
        }
        Muted("课程配色在编辑课程中调整。小组件支持独立设置浅色、深色、莫奈或跟随App。教务网页保留学校原色。")
    }
}

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
