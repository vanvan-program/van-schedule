// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.*
import app.van.timetable.ui.nexio.WallpaperStore
import app.van.timetable.ui.nexio.toCardSoftGlassStyle
import app.van.timetable.ui.nexio.vanSoftGlassBackdrop
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import kotlin.math.roundToInt

/**
 * 「外观设置」（二级页）。
 *
 * 原来这里是 6 个面板、十多个滑杆与开关连成一长条，想改「卡片圆角」得滚很久；现在这一页
 * 只留一张实时预览卡和四个入口，细节分别收进四个三级页：
 * 主题与壁纸 / 材质与卡片 / 字体与网格 / 视图与动效。
 */
@Composable
fun Appearance(p: Preferences, vm: VanViewModel, nav: (String) -> Unit) {
    val colors = LocalVan.current
    val previewBackdrop = rememberLayerBackdrop()
    var wallpaperBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(p.wallpaperUri) {
        wallpaperBitmap = WallpaperStore.loadImageBitmap(p.wallpaperUri, 1280)
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
            val pastelPreview = p.cardStyle != "solid"
            val glassOn = p.materialLevel != "none" && materialSupported && !pastelPreview
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
                        sampleCourseColor.copy(
                            alpha =
                                if (pastelPreview) PastelFillAlpha
                                else if (p.materialLevel == "none") 1f
                                else p.cardOpacity
                        ),
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
                when {
                    pastelPreview ->
                        Color(
                            CoursePalette.pastelTextArgb(
                                sampleCourseColor.toArgb().toLong() and 0xFFFFFFFFL,
                                sampleCourseColor.copy(alpha = PastelFillAlpha)
                                    .compositeOver(colors.background)
                                    .toArgb().toLong() and 0xFFFFFFFFL,
                                colors.dark,
                            )
                        )
                    p.cardTextColorMode == "light" -> Color.White
                    p.cardTextColorMode == "dark" -> Color(0xFF142036)
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

        SettingsGroup(
            title = "",
            rows =
                listOf(
                    SettingRow("appearanceTheme", "主题与壁纸", "深色 / 浅色、莫奈取色、壁纸亮度与模糊"),
                    SettingRow("appearanceCard", "材质与卡片", "玻璃材质、卡片着色、圆角与文字"),
                    SettingRow("appearanceFont", "字体与网格", "全局字号与节次行高"),
                    SettingRow("appearanceMotion", "视图与动效", "显示哪些内容、课间空档与动画"),
                ),
            onOpen = nav,
        )
        Muted("课程配色在编辑课程中调整。小组件支持独立设置浅色、深色、莫奈或跟随App。教务网页保留学校原色。")
    }
}

/** 「主题与壁纸」三级页。 */
@Composable
fun AppearanceThemePage(p: Preferences, vm: VanViewModel) {
    val context = LocalContext.current
    val pickWallpaper =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.work {
                    val savedPath = WallpaperStore.saveFromUri(context, uri)
                    vm.store.updatePreferences { it.copy(wallpaperUri = savedPath) }
                }
            }
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
    }
}

/** 「材质与卡片」三级页。 */
@Composable
fun AppearanceCardPage(p: Preferences, vm: VanViewModel) {
    val materialSupported = remember { isRenderEffectSupported() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
            Text("课程卡片效果与排版", fontWeight = FontWeight.Medium)
            Text("卡片风格")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pastel" to "柔和", "solid" to "实色").forEach { (id, label) ->
                    FilterChip(p.cardStyle == id, { vm.prefs { it.copy(cardStyle = id) } }, label = { Text(label) })
                }
            }
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
    }
}

/** 「字体与网格」三级页。 */
@Composable
fun AppearanceFontPage(p: Preferences, vm: VanViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
    }
}

/** 「视图与动效」三级页。 */
@Composable
fun AppearanceMotionPage(p: Preferences, vm: VanViewModel, schedule: Schedule?) {
    val canCollapseGaps = remember(schedule) { schedule?.let(GridTimeline::canCollapseGaps) ?: false }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                "${if (p.showCourseGaps == null) "当前自动判断。" else "当前使用手动设置。"}手动关闭后收紧空白课间和午休，仅为放不下的课程留出位置。空课节次仍显示。",
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
    }
}