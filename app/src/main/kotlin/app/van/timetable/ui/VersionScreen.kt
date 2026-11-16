// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import app.van.timetable.domain.AppRelease
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.UpdateCheck
import app.van.timetable.system.UpdateChecker
import app.van.timetable.system.UpdateInstaller
import androidx.compose.ui.Alignment
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 关于版本：显示本机版本信息，并在用户点击时联网检查 GitHub 发布页的最新版本。 */
@Composable
fun VersionScreen(nav: (String) -> Unit, p: Preferences, vm: VanViewModel) {
    val context = LocalContext.current
    val info = remember(context) { installedVersion(context) }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialog by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    var downloadPercent by remember { mutableStateOf<Int?>(null) }
    var installNote by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 点击「立即更新」：应用内下载安装包，再交给系统安装器覆盖安装。 */
    fun startUpdate(release: AppRelease) {
        dialog = false
        if (downloadPercent != null) return
        val asset = release.apk
        if (asset == null) {
            installNote = "这次发布没有附带安装包，只能改用下面的浏览器下载。"
            return
        }
        if (!UpdateInstaller.canInstall(context)) {
            needsPermission = true
            installNote = null
            return
        }
        needsPermission = false
        installNote = null
        downloadPercent = 0
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    UpdateInstaller.download(context, asset, release.version, p.useDownloadMirror) { percent ->
                        scope.launch(Dispatchers.Main) { downloadPercent = percent }
                    }
                }
            downloadPercent = null
            when (result) {
                is UpdateInstaller.Download.Ready -> {
                    val opened = UpdateInstaller.install(context, result.file)
                    val via = if (result.viaMirror) "（通过加速镜像下载，已校验）" else ""
                    installNote =
                        if (opened) "已打开系统安装界面$via，按提示点「安装」就能完成更新，课表和设置会保留。"
                        else "没能调起系统安装界面$via，请确认已允许安装未知应用，或改用浏览器下载。"
                }
                is UpdateInstaller.Download.Failed -> installNote = result.message
            }
        }
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
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(info.name) }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(Modifier.testTag("version-panel")) {
            Text("van课表 ${info.name}", style = MaterialTheme.typography.headlineSmall)
            Text("版本代码 ${info.code}")
            Muted("包名 ${context.packageName}")
            Muted("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}） · GPL-3.0-only")
        }
        Panel(Modifier.testTag("update-panel")) {
            Text("检查版本更新", fontWeight = FontWeight.Medium)
            Muted("点击后联网读取 GitHub 发布页的最新版本与更新说明；有新版本时可以直接在应用内下载，由系统弹窗确认安装。不上传课表、账号或设备信息。")
            Toggle(
                "国内加速下载",
                p.useDownloadMirror,
                { enabled -> vm.prefs { it.copy(useDownloadMirror = enabled) } },
                "直连 GitHub 失败或太慢时，依次改用公共加速镜像；安装包仍会校验 SHA-256 与签名，镜像无法篡改内容。",
            )
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
                UpdateState.Idle ->
                    Muted("尚未检查。最新版本来自 GitHub：github.com/${UpdateChecker.REPO}")
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
                        var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
                        var overflowing by remember(release.version) { mutableStateOf(false) }
                        Text("更新说明", fontWeight = FontWeight.Medium)
                        Text(
                            release.notes,
                            modifier = Modifier.testTag("version-notes"),
                            maxLines = if (expanded) Int.MAX_VALUE else 8,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                        )
                        if (expanded || overflowing)
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "收起更新说明" else "展开全部更新说明")
                            }
                    } else {
                        Muted("这条更新说明只包含版本号，请到发布页查看完整内容。")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { startUpdate(release) },
                            enabled = downloadPercent == null && release.apk != null,
                            modifier = Modifier.testTag("install-update"),
                        ) {
                            if (downloadPercent != null) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("正在下载 ${downloadPercent}%")
                            } else {
                                Text("立即更新")
                            }
                        }
                        TextButton(onClick = { openFailed = !openPage(release.pageUrl) }) {
                            Text("改用浏览器下载")
                        }
                    }
                    if (downloadPercent != null) {
                        LinearProgressIndicator(
                            progress = { (downloadPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth().testTag("update-progress"),
                        )
                    }
                    if (release.apk == null) {
                        Muted("这次发布没有附带安装包，应用内更新不可用，请用浏览器下载。")
                    }
                    if (needsPermission) {
                        Text("需要先允许安装未知应用", fontWeight = FontWeight.Medium)
                        Muted("Android 要求由你为 van课表单独开启这项权限；开启后回到这里再点「立即更新」。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { UpdateInstaller.openInstallSettings(context) }) { Text("去开启") }
                            TextButton(onClick = { needsPermission = false }) { Text("取消") }
                        }
                    }
                    if (installNote != null) Muted(installNote!!)
                    else if (!needsPermission) Muted("下载后由系统弹窗确认安装；包名与原签名不变，课表和设置会保留。")
                    if (openFailed) Muted("没有打开可用的浏览器，请手动访问：${release.pageUrl}")
                }
            }
        }
        Action("历史版本更新", "查看 ${info.name} 及以往版本的更新内容") { nav("changelog") }
        Action("开源与第三方许可", "查看本地许可声明") {
            context.startActivity(Intent(context, app.van.timetable.LicenseActivity::class.java))
        }
        Muted("检查更新只在点击时发起，本机不保存检查结果，也不在后台自动联网；下载的安装包只放在缓存目录，校验签名后才交给系统安装。")
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
                            Text(release.notes.lines().take(12).joinToString("\n"))
                        }
                        Spacer(Modifier.height(8.dp))
                        Muted("下载后由系统弹窗确认安装，课表与设置会保留。")
                    }
                },
                confirmButton = {
                    TextButton(
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