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
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
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
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    sealed interface Download {
        data class Ready(val file: File) : Download

        data class Failed(val message: String) : Download
    }

    /** 缓存目录里的安装包位置；同名文件存在说明上次已经下好。 */
    fun cachedFile(context: Context, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "van-timetable-${version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk")
    }

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
     * 下载安装包。[onProgress] 回调 0–100，可能在后台线程被调用。
     * 已存在且校验通过的缓存文件会直接复用，避免重复下载十几兆。
     */
    fun download(
        context: Context,
        asset: ApkAsset,
        version: String,
        onProgress: (Int) -> Unit = {},
    ): Download {
        val target = cachedFile(context, version)
        if (target.isFile && verifyFile(context, target, asset)) {
            onProgress(100)
            return Download.Ready(target)
        }
        target.delete()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "van-timetable/$version")
            }
            val code = connection.responseCode
            if (code !in 200..299) return Download.Failed(downloadStatusMessage(code))
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (written <= 0L) {
                temp.delete()
                return Download.Failed("下载到的安装包是空的，请稍后重试")
            }
            if (asset.sha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(asset.sha256, ignoreCase = true)) {
                    temp.delete()
                    return Download.Failed("安装包校验失败（SHA-256 不一致），已丢弃，请重试")
                }
            }
            val expectedSize = asset.size
            if (expectedSize != null && expectedSize > 0 && written != expectedSize) {
                temp.delete()
                return Download.Failed("安装包不完整（应为 $expectedSize 字节，实收 $written 字节），已丢弃")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return Download.Failed("无法保存安装包，请检查存储空间后重试")
            }
            if (!sameSigner(context, target)) {
                target.delete()
                return Download.Failed("安装包签名与本机版本不一致，已停止安装")
            }
            onProgress(100)
            return Download.Ready(target)
        } catch (e: InterruptedException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            return Download.Failed(describeDownloadError(e))
        } finally {
            connection?.disconnect()
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun signerBytes(info: PackageInfo): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        }

    private fun downloadStatusMessage(code: Int): String =
        when (code) {
            404 -> "发布页上没有这个安装包，可能已被替换，请到发布页手动下载"
            403, 429 -> "下载被 GitHub 限流了，请稍后再试"
            in 500..599 -> "GitHub 服务暂时不可用（HTTP $code）"
            else -> "下载失败：HTTP $code"
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
