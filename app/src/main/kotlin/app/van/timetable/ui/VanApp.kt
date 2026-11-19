// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.domain.*
import app.van.timetable.school.SchoolActivity
import app.van.timetable.system.*
import app.van.timetable.ui.navigation.VanNavHost
import app.van.timetable.ui.navigation.VanNavHostEnv
import app.van.timetable.ui.navigation.VanRoute
import app.van.timetable.ui.navigation.normalizeRoute
import app.van.timetable.ui.navigation.vanRouteOf
import app.van.timetable.ui.nexio.*
import app.van.timetable.widget.*
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.haooz.softglass.platform.isRenderEffectSupported
import com.haooz.softglass.shape.ContinuousRoundedRectangle
import java.time.*
import kotlinx.coroutines.*

/**
 * 应用外壳：主题、壁纸、玻璃材质、顶栏/底栏、返回栈与 Activity 结果回调都在这里，
 * 具体页面由 [VanNavHost] 按 [VanRoute] 分发。
 *
 * 0.4.0 及之前这个文件同时承担导航、四个文件回调、备份导出、分享、小组件说明与全部屏幕分发，
 * 现在只保留「壳」的职责，屏幕实现见 `ui/timetable/` 与各独立屏幕文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VanApp(
    vm: VanViewModel,
    initialCourse: String?,
    initialDate: String?,
    initialSchedule: String? = null,
    openTimetable: Boolean = false,
    launchSequence: Int = 0,
    initialSession: String? = null,
    initialOriginalDate: String? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val imported by vm.importData.collectAsStateWithLifecycle()
    val warnings by vm.importWarnings.collectAsStateWithLifecycle()
    val document by vm.documentSource.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable(stateSaver = VanRouteSaver) { mutableStateOf<VanRoute>(VanRoute.Home) }
    val stack =
        rememberSaveable(
            saver =
                listSaver<SnapshotStateList<String>, String>(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                )
        ) {
            mutableStateListOf<String>()
        }
    val screenStates = rememberSaveableStateHolder()
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var viewedScheduleId by rememberSaveable { mutableStateOf(initialSchedule) }
    var handledLaunch by rememberSaveable { mutableIntStateOf(-1) }
    var handledLaunchTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val launchTarget = listOf(initialCourse, initialDate, initialSchedule, openTimetable,
        initialSession, initialOriginalDate).toString()
    val displayed =
        if (viewedScheduleId == null) state.active
        else state.schedules.firstOrNull { it.id == viewedScheduleId }

    fun nav(target: String) {
        if (target == "schedules") viewedScheduleId = null
        stack += route.key
        // Each visit owns a draft; restored editors must never overwrite a previously saved course.
        route = vanRouteOf(normalizeRoute(target, newId()))
    }
    fun back() {
        if (route is VanRoute.CourseEdit || route is VanRoute.QuickAdd)
            screenStates.removeState("${route.key}:$tab:${displayed?.id.orEmpty()}:")
        route = stack.removeLastOrNull()?.let(::vanRouteOf) ?: VanRoute.Home
    }
    fun home() {
        stack.clear()
        route = VanRoute.Home
    }
    LaunchedEffect(Unit) { vm.messages.collect { message = it } }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(15_000)
        }
    }
    LaunchedEffect(
        state.ready,
        launchTarget,
        launchSequence,
    ) {
        if (!state.ready || (handledLaunch == launchSequence && handledLaunchTarget == launchTarget))
            return@LaunchedEffect
        handledLaunch = launchSequence
        handledLaunchTarget = launchTarget
        if (state.ready && openTimetable) {
            viewedScheduleId = initialSchedule
            home()
            tab = 0
        } else if (state.ready && initialCourse != null) {
            viewedScheduleId = initialSchedule
            home()
            tab = 0
            val targetSchedule = if (initialSchedule == null) state.active
                else state.schedules.firstOrNull { it.id == initialSchedule }
            if (targetSchedule?.courses?.any { it.id == initialCourse } == true) {
                val detail = if (initialDate == null) VanRoute.CourseDetail(initialCourse)
                    else VanRoute.CourseDetail(initialCourse, initialDate, initialSession,
                        initialOriginalDate.takeIf { initialSession != null })
                nav(detail.key)
            } else message = "这门课程或绑定的课表已删除"
        }
    }
    var backProgress by remember { mutableFloatStateOf(0f) }
    BackHandler(route != VanRoute.Home && !state.preferences.predictiveBack) { back() }
    PredictiveBackHandler(enabled = route != VanRoute.Home && state.preferences.predictiveBack) { progressFlow ->
        try {
            progressFlow.collect { event -> backProgress = event.progress }
            backProgress = 0f
            back()
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.work {
                    try {
                        val result =
                            DocumentFiles.read(context, uri) { vm.importProgress.value = it }
                        vm.importData.value = result.backup
                        vm.importWarnings.value = result.warnings
                        vm.documentSource.value = result.document
                        withContext(Dispatchers.Main) {
                            vm.documentDrafts.clear()
                            nav(if (result.document != null) "document" else "preview")
                        }
                    } finally {
                        vm.importProgress.value = ""
                    }
                }
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                vm.work {
                    val text = vm.store.backup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error("无法写入备份文件")
                    vm.messages.emit("完整备份已保存")
                }
        }
    val schoolLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data?.getStringExtra("result")
                val school = result.data?.getStringExtra("schoolName")
                if (text != null && school != null)
                    vm.work {
                        val draft = Importers.school(text, school, mondayNow())
                        vm.importData.value =
                            Backup(scope = "single", schedules = listOf(draft.schedule))
                        vm.importWarnings.value = draft.warnings
                        withContext(Dispatchers.Main) { nav("preview") }
                    }
            }
        }
    val c = LocalVan.current
    val p = state.preferences
    val materialLevel = LocalVanMaterialLevel.current
    val mainBackdrop = rememberLayerBackdrop()
    var wallpaperBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.wallpaperUri) {
        wallpaperBitmap = WallpaperStore.loadImageBitmap(p.wallpaperUri)
    }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHPx = with(density) { config.screenHeightDp.dp.toPx() }
    val cardGlassStyle = remember(p, c.dark) { p.toCardSoftGlassStyle(c.dark) }
    val glassSurface =
        if (materialLevel != GlassMaterialLevel.NONE) {
            rememberVanSoftGlassSurface(
                wallpaperBitmap = wallpaperBitmap,
                wallpaperScale = 1f,
                wallpaperOffset = Offset.Zero,
                screenWidthPx = screenWPx,
                screenHeightPx = screenHPx,
                wallpaperBrightness = (p.wallpaperBrightness - 1f) * 50f,
                style = cardGlassStyle,
            )
        } else null
    val bottomDragSelect = remember {
        DropdownPanelDragSelectState(
            axis = DragSelectAxis.Horizontal,
            maskMode = DropdownMaskMode.Sliding,
        )
    }
    val haptic = LocalHapticFeedback.current
    CompositionLocalProvider(
        LocalVanBackdrop provides mainBackdrop,
        LocalVanSoftGlassSurface provides glassSurface,
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    if (backProgress > 0f && route != VanRoute.Home) {
                        val s = 1f - 0.08f * backProgress
                        scaleX = s
                        scaleY = s
                        translationX = backProgress * 42f
                        clip = true
                        shape = ContinuousRoundedRectangle((backProgress * 28f).dp)
                    }
                }
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (materialLevel != GlassMaterialLevel.NONE) Modifier.layerBackdrop(mainBackdrop)
                        else Modifier
                    )
                    .background(Brush.verticalGradient(listOf(c.top, c.background)))
            ) {
                wallpaperBitmap?.let { bmp ->
                    val gain = p.wallpaperBrightness.coerceIn(0.3f, 1.4f)
                    Image(
                        bitmap = bmp,
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
                                if (p.wallpaperBlurDp > 0.5f && isRenderEffectSupported()) {
                                    m.blur(p.wallpaperBlurDp.dp)
                                } else m
                            },
                    )
                }
            }
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    if (route != VanRoute.Home)
                        ProgressiveBlurTopBar(
                            backdrop = mainBackdrop,
                            tintColor = c.top,
                            useFakeProgressiveBlur = !p.trueProgressiveBlur,
                            useGradientMask = materialLevel == GlassMaterialLevel.NONE,
                        ) {
                            TopAppBar(
                                title = { MotionText(route.title) },
                                navigationIcon = {
                                    IconButton(
                                        onClick = {
                                            (context as? ComponentActivity)
                                                ?.onBackPressedDispatcher
                                                ?.onBackPressed() ?: back()
                                        }
                                    ) {
                                        Icon(Icons.Outlined.ArrowBack, "返回")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            )
                        }
                },
                bottomBar = {
                    if (route == VanRoute.Home) {
                        // 悬浮导航岛：不再贴着屏幕左右与底边，而是居中收窄成一条悬浮胶囊，
                        // 四周边距明显、投影加深，和旧版贴边横条一眼能分开。
                        val navShape = ContinuousRoundedRectangle(30.dp)
                        val navBg = c.surface
                        CompositionLocalProvider(LocalDropdownPanelDragSelect provides bottomDragSelect) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(0.84f)
                                        .shadow(
                                            elevation = 22.dp,
                                            shape = navShape,
                                            clip = false,
                                            ambientColor = Color.Black.copy(alpha = 0.24f),
                                            spotColor = Color.Black.copy(alpha = 0.32f),
                                        )
                                        .clip(navShape)
                                        .then(
                                            if (materialLevel != GlassMaterialLevel.NONE) {
                                                Modifier.vanChromeGlass(
                                                    backdrop = mainBackdrop,
                                                    shape = navShape,
                                                    level = materialLevel,
                                                    isDark = c.dark,
                                                    fallbackColor = navBg,
                                                )
                                            } else {
                                                Modifier.background(navBg, navShape)
                                            }
                                        )
                                        .dropdownPanelSlidingMask(bottomDragSelect, ContinuousRoundedRectangle(20.dp))
                                        .dropdownPanelDragSelect(bottomDragSelect, { 1f }, haptic),
                                ) {
                                    VanTabBar(tab) { tab = it }
                                }
                            }
                        }
                    }
                },
            ) { insets ->
                Box(Modifier.padding(insets).fillMaxSize()) {
                    if (!state.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else {
                        val importKey = when (route) {
                            VanRoute.DocumentReview -> document?.id.orEmpty()
                            VanRoute.ImportPreview -> imported?.schedules?.firstOrNull()?.id.orEmpty()
                            VanRoute.ImportUpdate -> vm.importUpdateDraft?.id.orEmpty()
                            else -> ""
                        }
                        val destination = VanDestination(route, tab, displayed?.id, importKey, stack.size)
                        AnimatedContent(
                            targetState = destination,
                            contentKey = { it.key },
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val detail =
                                    initialState.route is VanRoute.CourseDetail ||
                                        targetState.route is VanRoute.CourseDetail
                                val direction = if (targetState.route == initialState.route) {
                                    if (targetState.tab > initialState.tab) 1 else -1
                                } else if (targetState.depth >= initialState.depth) 1 else -1
                                val motion = if (detail) {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                                } else {
                                    (fadeIn(tween(180)) + slideInHorizontally(
                                        tween(260, easing = FastOutSlowInEasing),
                                        initialOffsetX = { direction * minOf(it / 12, 64) },
                                    )) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(
                                        tween(260, easing = FastOutSlowInEasing),
                                        targetOffsetX = { -direction * minOf(it / 16, 48) },
                                    ))
                                }
                                motion.using(null)
                            },
                            label = "screen-navigation",
                        ) { screen ->
                            CompositionLocalProvider(LocalDetailVisibility provides this) {
                                screenStates.SaveableStateProvider(screen.key) {
                                    VanNavHost(
                                        VanNavHostEnv(
                                            destination = screen,
                                            state = state,
                                            vm = vm,
                                            now = now,
                                            imported = imported,
                                            warnings = warnings,
                                            document = document,
                                            openTimetable = openTimetable,
                                            initialDate = initialDate,
                                            launchSequence = launchSequence,
                                            nav = ::nav,
                                            back = ::back,
                                            onSelectSchedule = { id ->
                                                viewedScheduleId = null
                                                vm.prefs { it.copy(activeScheduleId = id) }
                                            },
                                            onUpdateSchedule = { vm.save(it) },
                                            onPickFile = { pick.launch(arrayOf("*/*")) },
                                            onSchoolEntry = { url ->
                                                schoolLauncher.launch(
                                                    Intent(context, SchoolActivity::class.java)
                                                        .putExtra("url", url)
                                                )
                                            },
                                            onExportBackup = {
                                                if (state.schedules.isNotEmpty())
                                                    export.launch("van课表-${LocalDate.now()}.van.json")
                                                else message = "还没有课表可备份"
                                            },
                                            onRestoreSnapshot = {
                                                vm.work {
                                                    vm.importData.value = vm.store.readBackupSnapshot()
                                                    vm.importWarnings.value =
                                                        listOf("这是上一次覆盖课表前的本机快照")
                                                    withContext(Dispatchers.Main) { nav("preview") }
                                                }
                                            },
                                            onImportApplied = {
                                                vm.importUpdateDraft = null
                                                vm.documentSource.value = null
                                                vm.documentDrafts.clear()
                                                stack.clear()
                                                route = VanRoute.ScheduleList
                                            },
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (busy)
                        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (importProgress.isNotBlank())
                                Text(
                                    importProgress,
                                    Modifier.background(c.surface).fillMaxWidth().padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        }
                }
            }
        }
    }
    message?.let { value ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("操作提示") },
            text = { Text(value, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

/** 目的地只保存 [VanRoute.key]，恢复时通过 [vanRouteOf] 还原，与旧字符串 route 存档兼容。 */
internal val VanRouteSaver =
    androidx.compose.runtime.saveable.Saver<VanRoute, String>(
        save = { it.key },
        restore = { vanRouteOf(it) },
    )
