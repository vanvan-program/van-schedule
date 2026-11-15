// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import app.van.timetable.domain.UpdateCheck
import app.van.timetable.system.UpdateChecker
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 关于版本：显示本机版本信息，并在用户点击时联网检查 GitHub 发布页的最新版本。 */
@Composable
fun VersionScreen(nav: (String) -> Unit) {
    val context = LocalContext.current
    val info = remember(context) { installedVersion(context) }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialog by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        copied = false
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
            Muted("点击后联网读取 GitHub 发布页的最新版本与更新说明；不上传课表、账号或设备信息。")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openFailed = !openPage(release.pageUrl) }) { Text("打开下载页") }
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("van课表下载地址", release.pageUrl)
                                )
                                copied = true
                            }
                        ) {
                            Text(if (copied) "链接已复制" else "复制下载链接")
                        }
                    }
                    if (openFailed) Muted("没有打开可用的浏览器，请手动访问：${release.pageUrl}")
                    else Muted("下载 APK 后覆盖安装；包名与原签名不变时课表和设置会保留，App 不会自动安装或静默下载。")
                }
            }
        }
        Action("历史版本更新", "查看 ${info.name} 及以往版本的更新内容") { nav("changelog") }
        Action("开源与第三方许可", "查看本地许可声明") {
            context.startActivity(Intent(context, app.van.timetable.LicenseActivity::class.java))
        }
        Muted("检查更新只在点击时发起，本机不保存检查结果，也不在后台自动联网。")
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
                        Muted("下载后覆盖安装即可保留课表与设置。")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog = false
                            openFailed = !openPage(release.pageUrl)
                        }
                    ) {
                        Text("打开下载页")
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