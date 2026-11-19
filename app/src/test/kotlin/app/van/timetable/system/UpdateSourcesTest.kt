package app.van.timetable.system

import app.van.timetable.domain.ApkAsset
import app.van.timetable.domain.UpdateCheck
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class UpdateSourcesTest {
    private val page = "https://github.com/vanvan-program/van-schedule/releases"
    private val meta = """{"tag_name":"v1.2.0","html_url":"$page/tag/v1.2.0","assets":[{"name":"test.apk","browser_download_url":"$page/download/v1.2.0/test.apk"}]}"""

    @Test fun checkUsesWorkingMirrorWhenOfficialApiFails() {
        val urls = mutableListOf<String>()
        val release = UpdateChecker.latestRelease("1.0", "auto", { url, _, _ ->
            urls += url
            if (urls.size == 1) throw IOException("offline")
            meta
        }, { error("should not reach the page") })
        assertEquals("1.2.0", release.version)
        assertEquals("https://gh-proxy.com/" + urls.first(), urls.last())
        assertEquals(2, urls.size)
    }

    @Test fun mirrorFirstRejectsOffRepositoryLinksAndOfficialOnlySkipsMirror() {
        for (choice in listOf(UpdateCheck.DEFAULT_MIRROR, UpdateCheck.GITHUB)) {
            val urls = mutableListOf<String>()
            val release = UpdateChecker.latestRelease("1.0", choice, { url, _, _ ->
                urls += url
                if (url.startsWith("https://gh-proxy.com/")) meta.replace("$page/download/", "https://example.org/download/") else meta
            }, { error("should not reach the page") })
            assertEquals("$page/download/v1.2.0/test.apk", release.apk?.url)
            assertEquals(if (choice == UpdateCheck.GITHUB) 1 else 2, urls.size)
        }
    }

    @Test fun lateSlowdownIsDetectedEvenAfterAFastStartAndWithoutContentLength() {
        val window = DownloadSpeedWindow(0, 0)
        assertFalse(window.isSlow(8_000, 7_000_000))
        assertFalse(window.isSlow(15_999, 7_012_000))
        assertTrue(window.isSlow(16_000, 7_020_000))
    }

    @Test fun switchesAfterHalfAndResumesOnAnotherSource() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val file = File.createTempFile("update-test", ".apk")
        var range: String? = null
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 0) // no Content-Length
            exchange.use { runCatching { it.responseBody.write(bytes) } }
        }
        server.createContext("/next") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            val start = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
            exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
            exchange.sendResponseHeaders(206, (bytes.size - start).toLong())
            exchange.use { it.responseBody.write(bytes, start, bytes.size - start) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val asset = ApkAsset("test.apk", "$base/slow", sha256 = "a".repeat(64))
            var clock = 0L
            val error = runCatching {
                transferApk(asset.url, asset, "1", file, true, { clock }) { written, total ->
                    assertNull(total)
                    if (written >= bytes.size / 2 && clock == 0L) clock = 8_000
                    else if (clock >= 8_000) clock += 8_000
                }
            }.exceptionOrNull()
            assertTrue(error is IOException)
            val received = file.length()
            assertTrue(received in bytes.size / 2L until bytes.size.toLong())
            assertTrue(transferApk("$base/next", asset, "1", file, false) { _, _ -> })
            assertEquals("bytes=$received-", range)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            server.stop(0)
            file.delete()
        }
    }

    @Test fun fullResponseToRangeReplacesPartialFile() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val file = File.createTempFile("update-test", ".apk")
        val content = "complete".toByteArray()
        server.createContext("/") { e ->
            e.sendResponseHeaders(200, content.size.toLong())
            e.use { it.responseBody.write(content) }
        }
        server.start()
        try {
            file.writeText("old")
            val url = "http://127.0.0.1:${server.address.port}/"
            assertFalse(transferApk(url, ApkAsset("test.apk", url, "a".repeat(64)), "1", file, false) { _, _ -> })
            assertArrayEquals(content, file.readBytes())
        } finally {
            server.stop(0)
            file.delete()
        }
    }
}
