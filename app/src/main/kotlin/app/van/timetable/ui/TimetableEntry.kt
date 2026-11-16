// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.runtime.Composable
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.Schedule
import java.time.Instant

/**
 * 课表页的兼容入口。
 *
 * 实现在 `app.van.timetable.ui.timetable`（`ui/timetable/` 下按职责拆成 8 个文件）。
 * 保留这个转发函数是为了让既有调用点（导航宿主与 instrumentation 测试）无需改 import；
 * 等调用点统一指向新包后，这个文件可以整份删除。
 */
@Composable
fun Timetable(
    s: Schedule,
    p: Preferences,
    now: Instant,
    nav: (String) -> Unit,
    initialDate: String? = null,
    schedules: List<Schedule> = listOf(s),
    onSelectSchedule: (String) -> Unit = {},
    onUpdateSchedule: (Schedule) -> Unit = {},
) = app.van.timetable.ui.timetable.Timetable(
    s = s,
    p = p,
    now = now,
    nav = nav,
    initialDate = initialDate,
    schedules = schedules,
    onSelectSchedule = onSelectSchedule,
    onUpdateSchedule = onUpdateSchedule,
)