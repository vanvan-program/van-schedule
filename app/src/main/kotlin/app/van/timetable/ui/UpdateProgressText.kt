// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import app.van.timetable.system.UpdateInstaller
import java.util.Locale

/**
 * 应用内更新的进度文案。
 *
 * 只有百分比是不够的：换源时进度会归零、校验时进度停在 100%，
 * 用户看到的只是一个乱跳或卡住的数字，所以每一阶段都要有一句人话说明现在在干什么。
 */

/** 更新按钮上的短标签。 */
internal fun updateButtonLabel(stage: UpdateInstaller.Stage?, percent: Int): String =
    when (stage) {
        null -> "准备下载…"
        is UpdateInstaller.Stage.Connecting -> "正在连接…"
        is UpdateInstaller.Stage.Downloading -> if (stage.total == null) "正在下载…" else "正在下载 $percent%"
        is UpdateInstaller.Stage.Verifying -> "正在校验…"
        UpdateInstaller.Stage.Ready -> "安装包已就绪"
    }

/** 进度条下面的一行说明。 */
internal fun updateStageHint(stage: UpdateInstaller.Stage?, percent: Int): String =
    when (stage) {
        null -> "正在准备下载安装包…"
        is UpdateInstaller.Stage.Connecting -> {
            val source = stage.sourceName ?: if (stage.viaMirror) "加速镜像" else "直连 GitHub"
            val saved = if (stage.retainedBytes > 0) "已保留 ${megaBytes(stage.retainedBytes)} · " else ""
            "${saved}正在连接 $source（${stage.index}/${stage.count}）…"
        }
        is UpdateInstaller.Stage.Downloading -> {
            val source = stage.sourceName ?: if (stage.viaMirror) "加速镜像" else "直连 GitHub"
            val total = stage.total
            if (total != null && total > 0) {
                "已下载 ${megaBytes(stage.bytes)} / ${megaBytes(total)} · $source · $percent%"
            } else {
                "已下载 ${megaBytes(stage.bytes)} · $source"
            }
        }
        is UpdateInstaller.Stage.Verifying -> "下载完成，正在校验安装包（大小、SHA-256 与签名）…"
        UpdateInstaller.Stage.Ready -> "校验通过，可以安装更新。"
    }

/** 13 595 842 字节 → `13.0 MB`；下载提示里统一用 MB，避免 KB/MB 来回跳。 */
internal fun megaBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
