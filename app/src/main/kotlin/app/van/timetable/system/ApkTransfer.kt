// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.system

import app.van.timetable.domain.ApkAsset
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** A fresh measurement every eight seconds keeps a fast start from masking a later stall. */
internal class DownloadSpeedWindow(startMs: Long, startBytes: Long) {
    private var since = startMs
    private var baseline = startBytes

    fun isSlow(nowMs: Long, bytes: Long): Boolean {
        val elapsed = nowMs - since
        if (elapsed < 8_000) return false
        val slow = (bytes - baseline) * 1000 < 60 * 1024L * elapsed
        since = nowMs
        baseline = bytes
        return slow
    }
}

/** Returns true if this response appended to a previously downloaded prefix. */
internal fun transferApk(
    source: String,
    asset: ApkAsset,
    version: String,
    partial: File,
    hasNext: Boolean,
    nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    progress: (Long, Long?) -> Unit,
): Boolean {
    var offset = if (asset.sha256 != null) partial.length() else 0L
    val expectedSize = asset.size
    if (expectedSize != null && offset >= expectedSize) offset = 0
    while (true) {
        val conn = URL(source).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = if (hasNext) 8_000 else 30_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", "van-timetable/$version")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val status = conn.responseCode
            val append = status == HttpURLConnection.HTTP_PARTIAL
            if (append || status == 416) {
                val match = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                val start = match?.groupValues?.get(1)?.toLongOrNull()
                val end = match?.groupValues?.get(2)?.toLongOrNull()
                val full = match?.groupValues?.get(3)?.toLongOrNull()
                if (!append || offset == 0L || start != offset || full == null || full <= offset ||
                    end != full - 1 || (expectedSize != null && full != expectedSize)
                ) {
                    if (offset == 0L) throw IOException("下载源返回了无效的文件范围")
                    offset = 0
                    continue
                }
            } else if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("下载源返回 HTTP $status")
            }
            var written = if (append) offset else 0L
            val total = expectedSize ?: conn.contentLengthLong.takeIf { it > 0 }?.plus(written)
            val window = DownloadSpeedWindow(nowMs(), written)
            progress(written, total)
            conn.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        if (total != null && written > total) throw IOException("安装包超过发布大小")
                        progress(written, total)
                        if (hasNext && written != total && window.isSlow(nowMs(), written)) {
                            throw IOException("当前下载源持续过慢")
                        }
                    }
                }
            }
            if (written == 0L || (total != null && written != total)) throw IOException("安装包下载不完整")
            return append
        } finally {
            conn.disconnect()
        }
    }
}
