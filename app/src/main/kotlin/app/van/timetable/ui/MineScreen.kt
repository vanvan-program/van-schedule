// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.softglass.shape.ContinuousRoundedRectangle

/**
 * 「设置」：分组式设置页。
 *
 * 一级页保留全部 12 个入口，按 课表配置 / 外观与提醒 / 数据 / 关于 四组排列；
 * 长页面的拆分放在各自的二级页里做（例如「外观设置」再分成主题、卡片、字体、动效四个三级页）。
 */
@Composable
fun Mine(state: VanState, nav: (String) -> Unit) {
    val c = LocalVan.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Text("设置", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
        ProfileHeader(
            title = state.active?.let { "${it.name}" } ?: "尚未启用课表",
            subtitle = state.active?.let { "${it.courses.size} 门课程 · 本地优先 · GPL-3.0" }
                ?: "本地优先 · 无广告 · GPL-3.0",
        )

        SettingsGroup(
            title = "课表配置",
            rows =
                listOf(
                    SettingRow("schedules", "课表管理", "新建、切换、重命名与删除"),
                    SettingRow("semester", "学期设置", "开学日期与总周数"),
                    SettingRow("routines", "作息管理", "每节课的起止时间"),
                    SettingRow("holidays", "节假日设置", "放假与调休日期"),
                    SettingRow("days", "调课管理", "单日停课、换课与补课"),
                ),
            onOpen = nav,
        )
        SettingsGroup(
            title = "外观与提醒",
            rows =
                listOf(
                    SettingRow("appearance", "外观设置", "主题、卡片、字体与动效"),
                    SettingRow("notifications", "提醒与小米手环", "上课提醒与推送"),
                    SettingRow("band", "手环课表", "把课程表发到手表"),
                    SettingRow("widgets", "桌面小部件", "添加课表小部件"),
                ),
            onOpen = nav,
        )
        SettingsGroup(
            title = "数据",
            rows =
                listOf(
                    SettingRow("backup", "备份与分享", "导出、导入与快照回滚"),
                ),
            onOpen = nav,
        )
        SettingsGroup(
            title = "关于",
            rows =
                listOf(
                    SettingRow("version", "关于版本", "更新记录与检查更新"),
                    SettingRow("about", "帮助与关于", "使用说明、开源许可"),
                ),
            onOpen = nav,
        )
        Text(
            "van课表 · GPL-3.0-only · 本地优先",
            fontSize = 12.sp,
            color = c.muted.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
}

/** 顶部身份卡：课表名 + 一行说明，圆角 20dp。 */
@Composable
private fun ProfileHeader(title: String, subtitle: String) {
    val c = LocalVan.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = ContinuousRoundedRectangle(20.dp),
        color = c.surface,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(c.blue.copy(alpha = 0.14f), ContinuousRoundedRectangle(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = c.blue,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted)
            }
        }
    }
}