// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.van.timetable.VanApplication
import app.van.timetable.domain.AppRelease
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.UpdateCheck
import app.van.timetable.system.UpdateChecker
import app.van.timetable.system.UpdateDownloads
import app.van.timetable.system.UpdateInstaller
import androidx.compose.ui.Alignment
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SourcePicker(label: String, selected: String, options: Map<String, String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label · ${options[selected] ?: options.values.first()} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    select(key)
                })
            }
        }
    }
}

/** 关于版本：显示本机版本信息，并在用户点击时联网检查 GitHub 发布页的最新版本。 */
@Composable
fun VersionScreen(nav: (String) -> Unit, p: Preferences, vm: VanViewModel) {
    val context = LocalContext.current
    val info = remember(context) { installedVersion(context) }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialog by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val downloads = (context.applicationContext as VanApplication).updateDownloads
    val task = downloads.task.collectAsStateWithLifecycle()
    val downloadRelease by remember(task) { derivedStateOf { task.value?.release } }
    val downloading by remember(task) { derivedStateOf { task.value?.state is UpdateDownloads.State.Busy } }
    val finished by remember(task) { derivedStateOf { (task.value?.state as? UpdateDownloads.State.Finished)?.result } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val transition = LocalDetailVisibility.current?.transition
    val visible = transition == null || transition.targetState == EnterExitState.Visible
    var autoInstall by remember { mutableStateOf<AppRelease?>(null) }
    var installNote by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    fun startUpdate(release: AppRelease) {
        dialog = false
        if (!downloads.start(release, p.useDownloadMirror, p.preferredDownloadSource)) return
        autoInstall = release
        installNote = null
        needsPermission = false
        scope.launch { scroll.animateScrollTo(0) }
    }

    fun installUpdate(release: AppRelease, ready: UpdateInstaller.Download.Ready) {
        if (!ready.file.isFile) {
            startUpdate(release)
            return
        }
        if (!UpdateInstaller.canInstall(context)) {
            needsPermission = true
            installNote = null
            return
        }
        needsPermission = false
        installNote = if (UpdateInstaller.install(context, ready.file))
            "已打开系统安装界面。按提示安装即可保留课表和设置。"
        else "没能打开系统安装界面，请重试或改用浏览器下载。"
    }

    LaunchedEffect(finished, autoInstall, visible) {
        if (!visible) autoInstall = null
        val release = autoInstall
        val ready = finished as? UpdateInstaller.Download.Ready
        if (release != null && release == downloadRelease && ready != null) {
            autoInstall = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) installUpdate(release, ready)
        }
    }
    LaunchedEffect(Unit) {
        if (downloads.task.value != null) scroll.scrollTo(0)
    }

    fun openPage(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun check() {
        if (state is UpdateState.Checking) return
        state = UpdateState.Checking
        openFailed = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(info.name, p.versionQuerySource) }
            val checkedAt = LocalTime.now().format(clock)
            state =
                when (result) {
                    is UpdateChecker.Result.Available -> {
                        dialog = true
                        UpdateState.Newer(result.release, checkedAt)
                    }
                    is UpdateChecker.Result.UpToDate -> UpdateState.Current(result.latestVersion, checkedAt)
                    is UpdateChecker.Result.Failed -> UpdateState.Failed(result.message)
                }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(Modifier.testTag("version-panel")) {
            Text("van课表 ${info.name}", style = MaterialTheme.typography.headlineSmall)
            Text("版本代码 ${info.code}")
            Muted("包名 ${context.packageName}")
            Muted("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}） · GPL-3.0-only")
        }
        downloadRelease?.let { release ->
            DownloadPanel(
                task, needsPermission, installNote, openFailed,
                retry = { startUpdate(release) },
                install = { installUpdate(release, it) },
                allowInstall = { UpdateInstaller.openInstallSettings(context) },
                browser = { openFailed = !openPage(release.pageUrl) },
            )
        }
        Panel(Modifier.testTag("update-panel")) {
            Text("检查版本更新", fontWeight = FontWeight.Medium)
            Muted("点击后联网读取 GitHub 发布页的最新版本与更新说明；有新版本时可以直接在应用内下载，由系统弹窗确认安装。不上传课表、账号或设备信息。")
            Toggle(
                "国内加速下载",
                p.useDownloadMirror,
                { enabled -> vm.prefs { it.copy(useDownloadMirror = enabled) } },
                "开启后优先使用所选下载源，连接失败或持续过慢时自动换源。关闭后仅从 GitHub 下载。",
            )
            if (p.useDownloadMirror) {
                SourcePicker(
                    "首选下载源",
                    p.preferredDownloadSource,
                    UpdateCheck.downloadSourceNames.associateWith { it },
                ) { name -> vm.prefs { it.copy(preferredDownloadSource = name) } }
            }
            SourcePicker(
                "版本查询源",
                p.versionQuerySource,
                mapOf("auto" to "GitHub 优先，镜像备用", UpdateCheck.DEFAULT_MIRROR to "gh-proxy.com 优先", UpdateCheck.GITHUB to "仅 GitHub"),
            ) { name -> vm.prefs { it.copy(versionQuerySource = name) } }
            Button(
                onClick = { check() },
                enabled = state !is UpdateState.Checking,
                modifier = Modifier.testTag("check-update"),
            ) {
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在检查…")
                } else {
                    Text("检查更新")
                }
            }
            when (val current = state) {
                UpdateState.Idle -> Muted(if (downloadRelease == null)
                    "尚未检查。最新版本来自 GitHub：github.com/${UpdateChecker.REPO}" else "可以重新检查最新发布版本。")
                UpdateState.Checking -> Muted("正在连接 GitHub 发布页…")
                is UpdateState.Current -> {
                    val localAhead = UpdateCheck.isNewer(info.name, current.latestVersion)
                    Text(
                        if (localAhead) "本机版本较新" else "已是最新版本",
                        color = LocalVan.current.blue,
                        fontWeight = FontWeight.Medium,
                    )
                    val detail =
                        if (localAhead) "发布页最新为 ${current.latestVersion}，暂无更新"
                        else "发布页最新为 ${current.latestVersion}"
                    Muted("$detail · 检查于 ${current.checkedAt}")
                }
                is UpdateState.Failed -> {
                    Text("检查失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    Muted(current.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { check() }) { Text("重试") }
                        TextButton(onClick = { openFailed = !openPage(UpdateChecker.RELEASES_URL) }) {
                            Text("打开发布页")
                        }
                    }
                    if (openFailed) Muted("没有打开可用的浏览器，请手动访问：${UpdateChecker.RELEASES_URL}")
                }
                is UpdateState.Newer -> {
                    val release = current.release
                    val published = remember(release.publishedAt) { releaseDate(release.publishedAt) }
                    Text("发现新版本 ${release.version}", color = LocalVan.current.blue, fontWeight = FontWeight.Medium)
                    Muted(
                        listOfNotNull(
                                published?.let { "发布于 $it" },
                                "检查于 ${current.checkedAt}",
                                "本机 ${info.name}",
                            )
                            .joinToString(" · ")
                    )
                    if (release.notes.isNotBlank()) {
                        Text("更新说明", fontWeight = FontWeight.Medium)
                        ReleaseNotesView(release.notes, maxBlocks = 8, tag = "version-notes")
                    } else {
                        Muted("这条更新说明只包含版本号，请到发布页查看完整内容。")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sameDownload = downloadRelease?.let { it.version == release.version && it.apk == release.apk } == true
                        if (!sameDownload) {
                            Button(
                                onClick = { startUpdate(release) },
                                enabled = !downloading && release.apk != null,
                                modifier = Modifier.testTag("install-update"),
                            ) {
                                Text("立即更新")
                            }
                        }
                        TextButton(onClick = { openFailed = !openPage(release.pageUrl) }) {
                            Text("改用浏览器下载")
                        }
                    }
                    if (release.apk == null) {
                        Muted("这次发布没有附带安装包，应用内更新不可用，请用浏览器下载。")
                    } else if (release.apk?.sha256 == null) {
                        // 走发布动态 / 跳转页兜底时拿不到附件摘要：直链按发布约定拼出，
                        // 下载后仍强制校验签名证书，只有原签名的包才装得上。
                        Muted("当前查询源没有提供 SHA-256，下载中断后需从头下载。安装前会核对包名、版本和签名。")
                    }
                    if (openFailed) Muted("没有打开可用的浏览器，请手动访问：${release.pageUrl}")
                }
            }
        }
        Action("历史版本更新", "查看 ${info.name} 及以往版本的更新内容") { nav("changelog") }
        Action("开源与第三方许可", "查看本地许可声明") {
            context.startActivity(Intent(context, app.van.timetable.LicenseActivity::class.java))
        }
    }

    if (dialog) {
        (state as? UpdateState.Newer)?.let { newer ->
            val release = newer.release
            AlertDialog(
                onDismissRequest = { dialog = false },
                title = { Text("发现新版本 ${release.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(release.title)
                        if (release.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            ReleaseNotesView(release.notes, maxBlocks = 6)
                        }
                        Spacer(Modifier.height(8.dp))
                        Muted("下载后由系统弹窗确认安装，课表与设置会保留。")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = release.apk != null && !downloading,
                        onClick = {
                            dialog = false
                            startUpdate(release)
                        }
                    ) {
                        Text("立即更新")
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = false }) { Text("稍后") } },
            )
        }
    }
}

@Composable
private fun DownloadPanel(
    current: State<UpdateDownloads.Task?>,
    needsPermission: Boolean,
    installNote: String?,
    browserError: Boolean,
    retry: () -> Unit,
    install: (UpdateInstaller.Download.Ready) -> Unit,
    allowInstall: () -> Unit,
    browser: () -> Unit,
) {
    val task = current.value ?: return
    Panel(Modifier.testTag("update-download")) {
        Text("下载 ${task.release.version}", style = MaterialTheme.typography.titleMedium)
        when (val state = task.state) {
            is UpdateDownloads.State.Busy -> {
                val stage = state.stage
                val progress = Modifier.fillMaxWidth().testTag("update-progress")
                Text(updateButtonLabel(stage, state.percent), fontWeight = FontWeight.Medium)
                if (stage is UpdateInstaller.Stage.Downloading && stage.total != null) {
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = progress)
                } else {
                    LinearProgressIndicator(modifier = progress)
                }
                Text(updateStageHint(stage, state.percent), color = LocalVan.current.muted, fontSize = 13.sp)
            }
            is UpdateDownloads.State.Finished -> when (val result = state.result) {
                is UpdateInstaller.Download.Ready -> {
                    Text("安装包已就绪，校验通过")
                    if (needsPermission) {
                        Muted("请为 van课表开启「允许安装未知应用」，再返回安装。")
                        OutlinedButton(onClick = allowInstall) { Text("去开启") }
                    }
                    Button(onClick = { install(result) }, modifier = Modifier.testTag("install-downloaded-update")) {
                        Text("安装更新")
                    }
                    installNote?.let { Muted(it) }
                }
                is UpdateInstaller.Download.Failed -> {
                    Text("下载失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("update-error"))
                    Muted(result.message)
                    if (result.retainedBytes > 0) {
                        Muted("已保留 ${megaBytes(result.retainedBytes)}，重试时会尝试续传。")
                    }
                    Button(onClick = retry, modifier = Modifier.testTag("retry-update")) { Text("重试下载") }
                }
            }
        }
        if (task.state is UpdateDownloads.State.Finished) {
            TextButton(onClick = browser) { Text("改用浏览器下载") }
            if (browserError) Muted("没有打开可用的浏览器，请手动访问 ${task.release.pageUrl}")
        }
    }
}

/** 检查更新的界面状态；只在当前页面内存中保留。 */
private sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class Current(val latestVersion: String, val checkedAt: String) : UpdateState

    data class Newer(val release: AppRelease, val checkedAt: String) : UpdateState

    data class Failed(val message: String) : UpdateState
}


private data class InstalledVersion(val name: String, val code: Long)

/** 发布动态里的发布时间是 UTC，转成本机日期显示；无法解析时退回原始日期部分。 */
private fun releaseDate(publishedAt: String): String? {
    val raw = publishedAt.trim()
    if (raw.isEmpty()) return null
    return runCatching {
            Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }
        .getOrNull() ?: raw.take(10).takeIf { it.length == 10 }
}

private fun installedVersion(context: Context): InstalledVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return InstalledVersion(
        name = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "未知版本",
        code = PackageInfoCompat.getLongVersionCode(packageInfo),
    )
}
