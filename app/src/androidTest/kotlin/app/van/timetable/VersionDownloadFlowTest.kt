// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.van.timetable.domain.ApkAsset
import app.van.timetable.domain.AppRelease
import app.van.timetable.domain.newId
import app.van.timetable.system.UpdateDownloads
import app.van.timetable.system.UpdateInstaller
import app.van.timetable.ui.updateButtonLabel
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionDownloadFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun keepsDownloadingOffPageAndRetriesTheSameSignedApkFromTheSavedOffset() {
        val context = ui.activity
        val app = context.application as VanApplication
        val downloads = app.updateDownloads
        val previousPrefs = runBlocking { app.store.prefs() }
        val version = requireNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val source = File(context.applicationInfo.publicSourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(source.inputStream(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) != -1) Unit
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val target = UpdateInstaller.cachedFile(context, version)
        val previousApk = target.takeIf { it.isFile }?.let {
            File(target.parentFile, "${target.name}.${newId()}.bak").also { backup ->
                check(it.renameTo(backup)) { "无法保留原更新缓存" }
            }
        }
        try {
            runBlocking { app.store.updatePreferences { it.copy(useDownloadMirror = false) } }
            DownloadServer(source).use { server ->
                val asset = ApkAsset("fixture.apk", server.url, sha, source.length())
                val release = AppRelease(version, "v$version", "续传验证", "", "", "", asset)
                fun progress() = (downloads.task.value?.state as? UpdateDownloads.State.Busy)

                ui.onNodeWithTag("tab-2").performClick()
                openVersion()
                assertTrue(downloads.start(release, useMirror = false, preferredSource = "GitHub"))
                ui.waitUntil(12_000) { ((progress()?.stage as? UpdateInstaller.Stage.Downloading)?.bytes ?: 0L) >= FIRST / 2 }
                ui.onNodeWithTag("update-progress").assertExists()
                leaveVersion()
                assertFalse(downloads.start(release, useMirror = false, preferredSource = "GitHub"))

                server.sendMore.countDown()
                ui.waitUntil(12_000) { ((progress()?.stage as? UpdateInstaller.Stage.Downloading)?.bytes ?: 0L) >= FIRST + MORE / 2 }
                openVersion()
                val busy = requireNotNull(progress())
                ui.onNodeWithText(updateButtonLabel(busy.stage, busy.percent)).assertIsDisplayed()
                ui.onNodeWithTag("update-progress").assertIsDisplayed()
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                    File(context.getExternalFilesDir(null), "update-retained-progress.png"),
                )

                server.disconnect.countDown()
                ui.waitUntil(12_000) { ui.onAllNodesWithTag("update-error").fetchSemanticsNodes().isNotEmpty() }
                val failure = (downloads.task.value?.state as UpdateDownloads.State.Finished).result
                    as UpdateInstaller.Download.Failed
                assertEquals((FIRST + MORE).toLong(), failure.retainedBytes)
                val partial = requireNotNull(UpdateInstaller.partialFile(context, version, asset))
                assertEquals(failure.retainedBytes, partial.length())
                assertFalse(target.exists())
                ui.onNodeWithText("已保留 4.0 MB，重试时会尝试续传。").assertIsDisplayed()
                ui.onNodeWithTag("retry-update").performClick()
                assertTrue("重试必须发出带偏移量的请求", server.retrySeen.await(12, SECONDS))
                assertEquals("bytes=${FIRST + MORE}-", server.retryRange)

                leaveVersion()
                server.finishRetry.countDown()
                ui.waitUntil(30_000) {
                    (downloads.task.value?.state as? UpdateDownloads.State.Finished)?.result is UpdateInstaller.Download.Ready
                }
                assertFalse(partial.exists())
                assertEquals(source.length(), target.length())
                assertNull(server.error)
                ui.onNodeWithText("关于版本").assertExists()
                openVersion()
                ui.onNodeWithText("安装包已就绪，校验通过").assertIsDisplayed()
                ui.onNodeWithTag("install-downloaded-update").assertIsDisplayed()
                ui.activityRule.scenario.recreate()
                ui.onNodeWithTag("install-downloaded-update").assertIsDisplayed()
                leaveVersion()
            }
        } finally {
            target.delete()
            if (previousApk != null) check(previousApk.renameTo(target)) { "无法还原更新缓存" }
            runBlocking { app.store.updatePreferences { previousPrefs } }
        }
    }

    private fun openVersion() {
        ui.onNodeWithText("关于版本").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("version-panel").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun leaveVersion() {
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("version-panel").fetchSemanticsNodes().isEmpty() }
    }

    private class DownloadServer(private val apk: File) : Closeable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 25_000 }
        val url = "http://127.0.0.1:${server.localPort}/fixture.apk"
        val sendMore = CountDownLatch(1)
        val disconnect = CountDownLatch(1)
        val retrySeen = CountDownLatch(1)
        val finishRetry = CountDownLatch(1)
        var retryRange: String? = null
            private set
        @Volatile var error: Throwable? = null
            private set

        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    assertNull(readRange(socket))
                    val output = socket.getOutputStream().buffered()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${apk.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                    RandomAccessFile(apk, "r").use { file ->
                        copy(file, output, FIRST.toLong())
                        check(sendMore.await(25, SECONDS))
                        copy(file, output, MORE.toLong())
                        check(disconnect.await(25, SECONDS))
                    }
                }
                server.accept().use { socket ->
                    retryRange = readRange(socket)
                    retrySeen.countDown()
                    check(finishRetry.await(25, SECONDS))
                    val offset = (FIRST + MORE).toLong()
                    val size = apk.length()
                    val output = socket.getOutputStream().buffered()
                    val response = "HTTP/1.1 206 Partial Content\r\nContent-Length: ${size - offset}\r\n" +
                        "Content-Range: bytes $offset-${size - 1}/$size\r\nConnection: close\r\n\r\n"
                    output.write(response.toByteArray())
                    RandomAccessFile(apk, "r").use { file ->
                        file.seek(offset)
                        copy(file, output, size - offset)
                    }
                }
            } catch (e: Throwable) {
                error = e
                retrySeen.countDown()
            }
        }

        private fun readRange(socket: Socket): String? {
            socket.soTimeout = 10_000
            return socket.getInputStream().bufferedReader().lineSequence().takeWhile { it.isNotEmpty() }
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }

        private fun copy(file: RandomAccessFile, output: OutputStream, size: Long) {
            var remaining = size
            val bytes = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = minOf(bytes.size.toLong(), remaining).toInt()
                file.readFully(bytes, 0, count)
                output.write(bytes, 0, count)
                remaining -= count
            }
            output.flush()
        }

        override fun close() {
            sendMore.countDown()
            disconnect.countDown()
            finishRetry.countDown()
            server.close()
            worker.join(5_000)
        }
    }

    companion object {
        private const val FIRST = 2 * 1024 * 1024
        private const val MORE = 2 * 1024 * 1024
    }
}
