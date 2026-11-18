// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.CourseEngine
import app.van.timetable.domain.Preferences
import app.van.timetable.domain.Schedule
import app.van.timetable.ui.CourseRow
import app.van.timetable.ui.LocalVan
import app.van.timetable.ui.Muted
import app.van.timetable.ui.Panel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 日列表：一天一张卡，可选按上午 / 下午 / 晚上分组。
 * 分组只是显示层，关闭后回到按开始时间平铺。
 */
@Composable
fun DayList(
    s: Schedule,
    date: java.time.LocalDate,
    now: Instant,
    nav: (String) -> Unit,
    modifier: Modifier = Modifier,
    p: Preferences = Preferences(),
) {
    val list = remember(s, date, p.globalDayOverrides) {
        CourseEngine.timetableDay(s, date, p.globalDayOverrides)
    }
    val holiday = remember(s, date) { CourseEngine.holiday(s, date) }
    val conflicts = remember(list) { CourseEngine.conflicts(list).flatten().map { it.id }.toSet() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (list.isEmpty() || holiday != null)
            item {
                Panel {
                    Text(if (holiday == null) "当天无课" else "放假期间，今日无课")
                    Muted(
                        holiday?.let { "${it.name} · ${it.from} 至 ${it.through}" }
                            ?: "可以添加课程或查看其他日期"
                    )
                }
            }
        if (p.groupDayListByPeriod && list.isNotEmpty()) {
            val morning = list.filter { it.start < LocalTime.of(12, 0) }
            val afternoon = list.filter { it.start >= LocalTime.of(12, 0) && it.start < LocalTime.of(18, 0) }
            val evening = list.filter { it.start >= LocalTime.of(18, 0) }
            listOf("上午" to morning, "下午" to afternoon, "晚上" to evening)
                .filter { it.second.isNotEmpty() }
                .forEach { (periodLabel, itemsInPeriod) ->
                    item(key = "period-$periodLabel") {
                        Text(
                            "$periodLabel（${itemsInPeriod.size}）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalVan.current.muted,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                    items(itemsInPeriod, key = { it.id }) { o ->
                        CourseRow(
                            o,
                            now,
                            { nav(occurrenceRoute(o)) },
                            ZoneId.of(s.zone),
                            holiday == null && o.id in conflicts,
                            inactive = holiday != null,
                            pastel = p.cardStyle != "solid",
                        )
                    }
                }
        } else {
            items(list, key = { it.id }) { o ->
                CourseRow(
                    o,
                    now,
                    { nav(occurrenceRoute(o)) },
                    ZoneId.of(s.zone),
                    holiday == null && o.id in conflicts,
                    inactive = holiday != null,
                    pastel = p.cardStyle != "solid",
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}