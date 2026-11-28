// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.van.timetable.widget.DualWidget
import app.van.timetable.widget.NextWidget
import app.van.timetable.widget.TodayWidget
import app.van.timetable.widget.WidgetPinResult
import app.van.timetable.widget.requestWidgetPin

/** 桌面小组件说明与添加入口。从 VanApp 迁出，保持原有文案与行为。 */
@Composable
fun WidgetGallery(state: VanState, vm: VanViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("今天的安排，桌面上就能看。", style = MaterialTheme.typography.headlineSmall)
        Muted("双日两列可独立上下滑动，今日已结束课程不显示。每个小组件可绑定不同课表，点击底部课表名称可以重新设置课表和主题。")
        Muted("课程和假期保存后自动同步，开始、结束与跨天自动刷新；无需手动点击更新。点击课程或日期打开所绑定的课表。")
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            ) {
                Text("准时刷新授权")
            }
            Muted("允许闹钟与提醒后可准时刷新；未授权或受系统省电限制时可能延迟。")
        }
        listOf(
                "双日课表" to DualWidget::class.java,
                "今日列表" to TodayWidget::class.java,
                "下一节课" to NextWidget::class.java,
            )
            .forEach { (label, clazz) ->
                var pinResult by rememberSaveable(label) { mutableStateOf<WidgetPinResult?>(null) }
                Panel {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(
                        if (clazz == DualWidget::class.java) "今天 ↕     明天 ↕"
                        else if (clazz == TodayWidget::class.java) "当前课 → 后续课程 ↕" else "正在上课或最近一节"
                    )
                    Button(
                        onClick = {
                            pinResult =
                                requestWidgetPin(
                                    isSupported = {
                                        AppWidgetManager.getInstance(context)
                                            .isRequestPinAppWidgetSupported
                                    },
                                    request = {
                                        AppWidgetManager.getInstance(context)
                                            .requestPinAppWidget(
                                                ComponentName(context, clazz), null, null
                                            )
                                    },
                                )
                        }
                    ) {
                        Text("添加到桌面")
                    }
                    pinResult?.let { Text(it.message) }
                }
            }
        Panel {
            Text("手动添加", fontWeight = FontWeight.Medium)
            Text("长按桌面空白处 → 小组件／添加小部件 → 找到 van课表 → 选择所需组件并拖到桌面。不同桌面的入口名称可能不同。")
            Text("如果列表中没有 van课表，请检查桌面的组件管理或添加权限。")
            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: RuntimeException) {
                        vm.work { vm.messages.emit("无法打开桌面，请按手机的主页键或使用返回桌面手势后手动添加。") }
                    }
                }
            ) {
                Text("返回桌面手动添加")
            }
        }
        OutlinedButton(
            onClick = { vm.refreshWidgets() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("刷新所有小组件")
        }
    }
}
