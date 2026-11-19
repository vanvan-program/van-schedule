// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.van.timetable.domain.ApkAsset
import app.van.timetable.domain.UpdateCheck
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 应用内更新：把安装包下载到缓存目录，再交给系统安装器覆盖安装。
 *
 * 不做静默安装——非 root 设备上系统一定会弹出确认，这一步无法绕过；
 * 这里省掉的是「打开浏览器、手找下载、再从文件管理器安装」。
 *
 * 安全上做两道校验：
 * 1. 文件大小与 SHA-256 与 GitHub 发布信息一致（早期发布没有摘要时跳过摘要，仅校验大小）；
 * 2. 安装包签名证书必须与本机已安装版本完全一致，否则删除并拒绝安装。
 */
object UpdateInstaller {

    sealed interface Download {
        /** [viaMirror] 表示这份安装包是从公共加速镜像取到的，不是 GitHub 直连。 */
        data class Ready(val file: File, val viaMirror: Boolean = false) : Download

        data class Failed(val message: String, val retainedBytes: Long = 0) : Download
    }

    /**
     * 下载阶段。界面靠它区分「正在连接」「正在下载」「正在校验」，
     * 否则换源时进度归零、校验时停在 100%，用户看到的只是一个不动或乱跳的百分比。
     */
    sealed interface Stage {
        /** 正在连接某个下载地址。[index] 从 1 开始，[count] 是本次可用的地址总数。 */
        data class Connecting(
            val viaMirror: Boolean, val index: Int, val count: Int, val sourceName: String? = null,
            val retainedBytes: Long = 0,
        ) : Stage

        /** 正在下载。[bytes] 已收字节数，[total] 是总字节数（服务器没给长度时为 null）。 */
        data class Downloading(val viaMirror: Boolean, val bytes: Long, val total: Long?, val sourceName: String? = null) : Stage

        /** 下载完成，正在校验大小、SHA-256 与签名。 */
        data class Verifying(val viaMirror: Boolean) : Stage

        /** 安装包已就绪（含直接复用缓存的情况）。 */
        data object Ready : Stage
    }

    /** 缓存目录里的安装包位置；同名文件存在说明上次已经下好。 */
    fun cachedFile(context: Context, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "van-timetable-${version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk")
    }

    internal fun partialFile(context: Context, version: String, asset: ApkAsset): File? {
        val digest = UpdateCheck.normalizeSha256(asset.sha256) ?: return null
        val identity = listOf(version, asset.name, asset.url.trim(), digest).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHex()
        return File(transferDirectory(context), "$hash.part.apk")
    }

    private fun transferDirectory(context: Context): File = File(context.cacheDir, "update-transfers")

    /** 是否允许安装未知来源应用；Android 8 起需要用户按应用单独授权。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳到系统的「安装未知应用」授权页。 */
    fun openInstallSettings(context: Context): Boolean =
        runCatching {
                context.startActivity(
                    Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }
            .getOrDefault(false)

    /**
     * 下载安装包。[onProgress] 回调 0–100，可能在后台线程被调用；[onStage] 报告当前阶段，
     * 供界面显示「正在连接 / 正在下载 x MB / 正在校验」这类提示。
     * 已存在且校验通过的缓存文件会直接复用，避免重复下载十几兆。
     *
     * 国内直连 GitHub 常常很慢，所以按 [UpdateCheck.downloadSources] 的顺序换源：
     * 先尝试用户选择的下载源，再依次尝试其余地址。无论从哪下载，最后都要过摘要与签名校验。
     */
    @Synchronized
    fun download(
        context: Context,
        asset: ApkAsset,
        version: String,
        useMirror: Boolean = true,
        preferredSource: String = UpdateCheck.DEFAULT_MIRROR,
        onProgress: (Int) -> Unit = {},
        onStage: (Stage) -> Unit = {},
    ): Download {
        val checkedAsset = asset.copy(sha256 = UpdateCheck.normalizeSha256(asset.sha256), size = asset.size?.takeIf { it > 0 })
        val target = cachedFile(context, version)
        val saved = partialFile(context, version, checkedAsset)
        if (target.isFile && verifyFile(context, target, checkedAsset) && matchesRelease(context, target, version)) {
            saved?.delete()
            onProgress(100)
            onStage(Stage.Ready)
            return Download.Ready(target)
        }
        val sources = UpdateCheck.downloadSources(checkedAsset.url, useMirror, preferredSource)
        if (sources.isEmpty()) return Download.Failed("没有可用的下载地址")
        val directory = transferDirectory(context)
        val partial = try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("下载缓存不可用")
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".part.apk") && it != saved }
                ?.forEach { it.delete() }
            saved ?: File.createTempFile("update-", ".part.apk", directory)
        } catch (e: IOException) {
            return Download.Failed("无法创建下载文件，请检查存储空间")
        }
        fun finish(mirror: Boolean): Download {
            if (!partial.renameTo(target)) {
                return Download.Failed("无法保存安装包，请检查存储空间", if (saved == null) 0 else partial.length())
            }
            onProgress(100)
            onStage(Stage.Ready)
            return Download.Ready(target, mirror)
        }
        var failure = "下载失败，请稍后重试"
        try {
            if (partial.isFile && verifyFile(context, partial, checkedAsset) && matchesRelease(context, partial, version)) {
                return finish(false)
            }
            if (checkedAsset.size?.let { partial.length() >= it } == true && !partial.delete()) {
                return Download.Failed("无法清理下载缓存")
            }
            for ((index, source) in sources.withIndex()) {
                val mirror = source != asset.url.trim()
                try {
                    val host = if (mirror) URL(source).host else "直连 GitHub"
                    onStage(Stage.Connecting(mirror, index + 1, sources.size, host,
                        if (saved == null) 0 else partial.length()))
                    var appended: Boolean
                    do {
                        var lastPercent = -1
                        var lastBytes = -262144L
                        appended = transferApk(source, checkedAsset, version, partial, index < sources.lastIndex) { bytes, total ->
                            val percent = if (total != null) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent || (total == null && bytes - lastBytes >= 262144)) {
                                onProgress(percent)
                                onStage(Stage.Downloading(mirror, bytes, total, host))
                                lastPercent = percent
                                lastBytes = bytes
                            }
                        }
                        onStage(Stage.Verifying(mirror))
                        if (verifyFile(context, partial, checkedAsset) && matchesRelease(context, partial, version)) {
                            return finish(mirror)
                        }
                        // A bad prefix from the previous source must not spoil a good mirror.
                        if (!partial.delete()) return Download.Failed("无法清理下载缓存")
                        failure = "安装包的校验值、签名或版本不符，请重试"
                    } while (appended)
                } catch (e: Exception) {
                    if (e is InterruptedException || e is CancellationException) throw e
                    failure = describeDownloadError(e)
                }
            }
            val retained = if (saved == null) 0 else partial.length()
            return Download.Failed(failure, retained)
        } finally {
            if (saved == null || partial.length() == 0L) partial.delete()
        }
    }

    private fun matchesRelease(context: Context, apk: File, version: String): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == context.packageName && info.versionName == version
    }

    /**
     * 调起系统安装器。系统仍会显示一次「是否安装」的确认，这是 Android 的硬性要求。
     * 返回 false 表示没有可用的安装界面（例如被策略禁用）。
     */
    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 安装包与本机已安装版本是否同一个签名证书。 */
    internal fun sameSigner(context: Context, apk: File): Boolean {
        val manager = context.packageManager
        val installed = runCatching { manager.getPackageInfo(context.packageName, signFlags()) }.getOrNull() ?: return false
        val archive = runCatching { manager.getPackageArchiveInfo(apk.absolutePath, signFlags()) }.getOrNull() ?: return false
        val a = signerBytes(installed) ?: return false
        val b = signerBytes(archive) ?: return false
        return a.contentEquals(b)
    }

    private fun verifyFile(context: Context, file: File, asset: ApkAsset): Boolean {
        val expectedSize = asset.size
        if (expectedSize != null && expectedSize > 0 && file.length() != expectedSize) return false
        if (asset.sha256 != null) {
            val actual = runCatching { sha256(file) }.getOrNull() ?: return false
            if (!actual.equals(asset.sha256, ignoreCase = true)) return false
        }
        return sameSigner(context, file)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun signFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun signerBytes(info: PackageInfo): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        }

    private fun describeDownloadError(error: Throwable): String =
        when (error) {
            is SocketTimeoutException -> "下载超时，请检查网络后重试"
            is UnknownHostException -> "无法解析下载地址，请检查网络连接"
            is ConnectException -> "无法连接下载服务器，请检查网络或代理设置"
            is SSLException -> "与下载服务器的安全连接失败，请稍后重试"
            else -> "下载失败：${error.message ?: error.javaClass.simpleName}"
        }
}
