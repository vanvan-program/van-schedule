// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.Schedule

/**
 * 备份与恢复。
 *
 * 0.4.0 时这段界面内联在 VanApp 的导航分支里，同时夹着三个 Activity 结果回调与文件读写。
 * 现在界面只负责展示与发出意图：导出、选择文件、恢复覆盖前快照都由 [VanApp] 提供的回调执行，
 * 「还没有课表可备份」之类的判断也留在回调里，界面不再重复业务条件。
 */
@Composable
fun BackupScreen(
    active: Schedule?,
    nav: (String) -> Unit,
    onExport: () -> Unit,
    onPickFile: () -> Unit,
    onRestoreSnapshot: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Action("导出完整备份", "包含全部课表、作息、调课和设置") { onExport() }
        Action("从文件恢复或导入", "先预览，再确认是否覆盖") { onPickFile() }
        Action("恢复上一次覆盖前快照", "替换前自动保存在本机") { onRestoreSnapshot() }
        active?.let { s -> Action("分享当前课表", s.name) { nav("share:${s.id}") } }
        Muted("备份文件不含教务登录Cookie、密码和系统授权。当前不提供自动云同步。")
    }
}