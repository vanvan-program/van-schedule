package app.van.timetable

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.van.timetable.system.UpdateInstaller
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 应用内更新的落地检查：缓存目录要能被 FileProvider 共享出去（否则系统安装器拿不到文件），
 * 并且签名校验要真的能区分「本应用的包」和随便一个文件。
 */
class InAppUpdateInstallerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<File>()

    @After fun cleanUp() {
        created.forEach { it.delete() }
    }

    private fun tempApk(version: String, bytes: ByteArray): File =
        UpdateInstaller.cachedFile(context, version).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
            created += this
        }

    @Test fun cachedApkIsShareableThroughFileProvider() {
        val file = tempApk("9.9.9-test", byteArrayOf(1, 2, 3, 4))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        assertTrue("文件必须能通过 content:// 共享给系统安装器，实际：$uri", uri.toString().startsWith("content://"))
        assertEquals("updates", file.parentFile?.name)
    }

    @Test fun signerCheckAcceptsOwnPackageAndRejectsOtherFiles() {
        val own = File(context.applicationInfo.publicSourceDir)
        assertTrue("本应用自己的 APK 应当通过签名校验", UpdateInstaller.sameSigner(context, own))
        val junk = tempApk("junk", ByteArray(128) { 7 })
        assertFalse("随便一个文件不应通过签名校验", UpdateInstaller.sameSigner(context, junk))
    }

    @Test fun installPermissionCheckDoesNotThrow() {
        // 只验证调用安全；模拟器上是否已授权不影响这里。
        UpdateInstaller.canInstall(context)
    }

    @Test fun downloadFailsCleanlyWhenEverySourceIsUnreachable() {
        // 不给镜像、也给一个不存在的域名：应当返回 Failed 而不是抛异常或留下半成品。
        val asset =
            app.van.timetable.domain.ApkAsset(
                name = "van-schedule-test-arm64.apk",
                url = "https://mirror-does-not-exist.invalid/van-schedule-test.apk",
                sha256 = "0".repeat(64),
                size = 1024,
            )
        var lastPercent = -1
        val result =
            UpdateInstaller.download(context, asset, "9.9.9-none", useMirror = false, onProgress = { lastPercent = it })
        assertTrue("应当干净地失败：$result", result is UpdateInstaller.Download.Failed)
        val leftover = UpdateInstaller.cachedFile(context, "9.9.9-none")
        assertFalse("失败后不能留下安装包", leftover.exists())
        assertFalse("失败后不能留下半成品", File(leftover.parentFile, "${leftover.name}.part").exists())
    }

    @Test fun downloadReportsConnectingStageBeforeGivingUp() {
        val asset =
            app.van.timetable.domain.ApkAsset(
                name = "van-schedule-test-arm64.apk",
                url = "https://mirror-does-not-exist.invalid/van-schedule-test.apk",
                sha256 = "0".repeat(64),
                size = 1024,
            )
        val stages = mutableListOf<UpdateInstaller.Stage>()
        val result =
            UpdateInstaller.download(
                context,
                asset,
                "9.9.9-stage",
                useMirror = false,
                onProgress = {},
                onStage = { stages += it },
            )
        assertTrue("应当干净地失败：$result", result is UpdateInstaller.Download.Failed)
        val first = stages.firstOrNull()
        assertTrue("失败之前必须先报「正在连接」，界面才会显示提示：$stages", first is UpdateInstaller.Stage.Connecting)
        assertEquals(1, (first as UpdateInstaller.Stage.Connecting).index)
        assertFalse("失败时不能报就绪", stages.any { it is UpdateInstaller.Stage.Ready })
    }
}
