// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.van.timetable.domain.CourseSession
import app.van.timetable.domain.Occurrence
import app.van.timetable.domain.Schedule
import app.van.timetable.domain.TimeMode
import app.van.timetable.ui.weekdays
import com.haooz.softglass.shape.ContinuousRoundedRectangle

/** 拖拽中待确认的落点与冲突课程。 */
internal data class PendingDragConflict(
    val source: Occurrence,
    val fromWeek: Int,
    val targetWeek: Int,
    val targetDay: Int,
    val targetStartSection: Int,
    val targetEndSection: Int,
    val conflicts: List<CourseSession>,
)

/** 周次直跳弹窗：学期内任意周一次点选。 */
@Composable
internal fun WeekSelectorDialog(
    week: Int,
    maxWeeks: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ContinuousRoundedRectangle(24.dp),
        title = { Text("选择周次") },
        text = {
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items((1..maxWeeks).toList()) { w ->
                    TextButton(
                        onClick = { onSelect(w) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (w == week) "✓ 第${w}周" else "第${w}周")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 跨周调课落到已有课程的格子上时，让用户选择「互换」或「覆盖」。
 * 只有冲突课程是可整周挪动的节次课时才提供互换。
 */
@Composable
internal fun DragConflictDialog(
    conflict: PendingDragConflict,
    s: Schedule,
    onSwap: (CourseSession) -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val conflictNames =
        conflict.conflicts.mapNotNull { cs ->
            s.courses.firstOrNull { it.id == cs.courseId }?.name
        }.distinct().joinToString("、")
    val canSwap =
        conflict.conflicts.size == 1 &&
            conflict.conflicts.first().mode == TimeMode.SECTIONS &&
            conflict.conflicts.first().date == null
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ContinuousRoundedRectangle(24.dp),
        title = { Text("课程时间冲突") },
        text = {
            Text(
                "第${conflict.targetWeek}周 ${weekdays[conflict.targetDay - 1]} 第${conflict.targetStartSection}–${conflict.targetEndSection}节已有《${conflictNames.ifBlank { "其他课程" }}》，请选择处理方式："
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canSwap) {
                    TextButton(onClick = { onSwap(conflict.conflicts.first()) }) { Text("互换位置") }
                }
                Button(onClick = onOverwrite) { Text("覆盖原课程") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}