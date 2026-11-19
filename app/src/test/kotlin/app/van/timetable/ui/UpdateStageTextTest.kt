// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import app.van.timetable.system.UpdateInstaller.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内更新的进度文案。
 *
 * 这几个串是用户下载时唯一能看到的东西：换源时进度会归零、校验时进度停在 100%，
 * 所以每个阶段必须换一句话，不能让界面一直显示「正在下载 0%」。
 */
class UpdateStageTextTest {

    @Test
    fun buttonLabelFollowsStage() {
        assertEquals("准备下载…", updateButtonLabel(null, 0))
        assertEquals("正在连接…", updateButtonLabel(Stage.Connecting(viaMirror = false, index = 1, count = 3), 0))
        assertEquals("正在下载 42%", updateButtonLabel(Stage.Downloading(false, 6_000_000, 13_595_842), 42))
        assertEquals("正在校验…", updateButtonLabel(Stage.Verifying(false), 100))
        assertEquals("安装包已就绪", updateButtonLabel(Stage.Ready, 100))
    }

    @Test
    fun hintSeparatesDirectDownloadFromMirrorFallback() {
        val direct = updateStageHint(Stage.Connecting(viaMirror = false, index = 1, count = 3), 0)
        assertTrue(direct, direct.contains("直连 GitHub"))
        val mirror = updateStageHint(Stage.Connecting(viaMirror = true, index = 2, count = 3), 0)
        assertTrue(mirror, mirror.contains("加速镜像") && mirror.contains("2/3"))
    }

    @Test
    fun downloadingHintShowsBytesAndPercent() {
        val hint = updateStageHint(Stage.Downloading(false, 6_291_456, 13_595_842), 46)
        assertTrue(hint, hint.contains("6.0 MB") && hint.contains("13.0 MB") && hint.contains("46%"))
        // 服务器没报总长度时就只显示已下载量，不能编一个假的百分之多少。
        val unknown = updateStageHint(Stage.Downloading(true, 2_097_152, null), 0)
        assertTrue(unknown, unknown.contains("2.0 MB") && !unknown.contains("%"))
    }

    @Test
    fun megaBytesKeepsOneDecimal() {
        assertEquals("13.0 MB", megaBytes(13_595_842))
        assertEquals("0.0 MB", megaBytes(0))
        assertEquals("1.5 MB", megaBytes(1_572_864))
    }
}
