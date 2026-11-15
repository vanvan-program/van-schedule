// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import app.van.timetable.domain.AppRelease
import app.van.timetable.domain.UpdateCheck
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 读取 GitHub 发布页上的最新版本。
 *
 * 只在用户点击“检查更新”时联网，只读取公开的版本号与更新说明；
 * 不上传课表、账号、设备标识或检查记录，也不在后台自动请求。
 */
object UpdateChecker {
    const val REPO = "vanvan-program/van-schedule"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val FEED_URL = "https://github.com/$REPO/releases.atom"
    private const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_BODY_CHARS = 512 * 1024

    sealed interface Result {
        /** 发布页最新版本不高于本机版本。 */
        data class UpToDate(val latestVersion: String) : Result

        /** 发布页有更新，附带版本号、更新说明和下载页。 */
        data class Available(val release: AppRelease) : Result

        /** 没有拿到版本号；[message] 可直接展示给用户。 */
        data class Failed(val message: String) : Result
    }

    /**
     * 检查最新版本。[currentVersion] 是本机 versionName，仅在本地比较，不会发送。
     * 必须在后台线程调用，函数内部会阻塞等待网络。
     */
    fun check(currentVersion: String): Result {
        val release =
            try {
                latestRelease(currentVersion)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                return Result.Failed(describe(e))
            }
        return if (UpdateCheck.isNewer(release.version, currentVersion)) Result.Available(release)
        else Result.UpToDate(release.version)
    }

    private fun latestRelease(currentVersion: String): AppRelease {
        var feedError: Exception? = null
        // 发布动态不需要授权，也没有 GitHub API 的按小时限流，优先使用。
        try {
            val xml = request(FEED_URL, "application/atom+xml", currentVersion)
            UpdateCheck.parseAtomRelease(xml)?.let { return it }
            feedError = IOException("发布动态中没有可用的版本号")
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            feedError = e
        }
        // 发布动态不可解析时，用“最新发布页”的跳转地址兜底，只能拿到版本号和下载页。
        try {
            val finalUrl = followLatestPage(currentVersion)
            UpdateCheck.parseLatestPageUrl(finalUrl)?.let { return it }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw feedError ?: e
        }
        throw feedError ?: IOException("无法确定最新版本")
    }

    private fun request(url: String, accept: String, currentVersion: String): String {
        val connection = open(url, accept, currentVersion)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(statusMessage(code))
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = reader.readText()
                if (body.length > MAX_BODY_CHARS) body.take(MAX_BODY_CHARS) else body
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun followLatestPage(currentVersion: String): String {
        val connection = open(LATEST_URL, "text/html", currentVersion)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(statusMessage(code))
            return connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String, currentVersion: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", "van-timetable/$currentVersion")
        }

    private fun statusMessage(code: Int): String =
        when (code) {
            403, 429 -> "GitHub 暂时限制了查询频率，请稍后再试"
            404 -> "没有找到发布页，仓库地址可能已变更"
            in 500..599 -> "GitHub 服务暂时不可用（HTTP $code）"
            else -> "发布页返回 HTTP $code"
        }

    private fun describe(error: Throwable): String =
        when (error) {
            is SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
            is UnknownHostException -> "无法解析 GitHub 域名，请检查网络连接"
            is ConnectException -> "无法连接 GitHub，请检查网络或代理设置"
            is SSLException -> "与 GitHub 的安全连接失败，请稍后重试"
            is IOException ->
                error.message?.takeIf { it.isNotBlank() } ?: "网络请求失败，请稍后重试"
            else -> "检查失败：${error.message ?: error.javaClass.simpleName}"
        }
}