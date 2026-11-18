// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.van.timetable.domain.InlineRun
import app.van.timetable.domain.ReleaseBlock
import app.van.timetable.domain.ReleaseNotes

/**
 * 更新说明的渲染件。
 *
 * 发布说明是 Markdown（`##` 标题、`**粗体**`、`|` 表格、行内代码）。以前整段塞进一个 `Text`，
 * 界面上看到的是 `## 改了什么` 和一堆竖线；现在解析成段落分别排版：
 * 标题加粗、列表带项目符号、表格按行铺开、行内代码用等宽字体。
 *
 * [maxBlocks] 是折叠时显示的段数，超过就出现「展开全部更新说明」。
 */
@Composable
fun ReleaseNotesView(
    notes: String,
    modifier: Modifier = Modifier,
    maxBlocks: Int = 8,
    tag: String? = null,
) {
    val c = LocalVan.current
    val blocks = remember(notes) { ReleaseNotes.parse(notes) }
    if (blocks.isEmpty()) return
    var expanded by rememberSaveable(notes) { mutableStateOf(false) }
    val shown = if (expanded) blocks else blocks.take(maxBlocks)
    Column(
        modifier.then(if (tag == null) Modifier else Modifier.testTag(tag)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { block ->
            when (block) {
                is ReleaseBlock.Heading ->
                    Text(
                        block.runs.toAnnotated(),
                        fontSize = if (block.level <= 2) 14.sp else 13.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (block.level <= 2) c.text else c.muted,
                        modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
                    )

                is ReleaseBlock.Bullet ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("•", fontSize = 12.sp, lineHeight = 18.sp, color = c.muted)
                        Spacer(Modifier.width(6.dp + (block.depth * 12).dp))
                        Text(block.runs.toAnnotated(), fontSize = 12.sp, lineHeight = 18.sp)
                    }

                is ReleaseBlock.Numbered ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${block.number}.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = c.muted,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(block.runs.toAnnotated(), fontSize = 12.sp, lineHeight = 18.sp)
                    }

                is ReleaseBlock.Paragraph ->
                    Text(block.runs.toAnnotated(), fontSize = 12.sp, lineHeight = 18.sp)

                is ReleaseBlock.Divider ->
                    HorizontalDivider(
                        Modifier.padding(vertical = 2.dp),
                        thickness = 0.6.dp,
                        color = c.muted.copy(alpha = 0.2f),
                    )

                is ReleaseBlock.Table -> {
                    if (block.header.isNotEmpty()) {
                        Text(
                            block.header.joinToString(" · "),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.muted,
                        )
                    }
                    block.rows.forEach { row ->
                        Text(
                            row.filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
        if (blocks.size > maxBlocks) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Text(if (expanded) "收起更新说明" else "展开全部更新说明", fontSize = 12.sp)
            }
        }
    }
}

/** 行内片段拼成带样式的文本：加粗、等宽代码。 */
private fun List<InlineRun>.toAnnotated(): AnnotatedString = buildAnnotatedString {
    forEach { run ->
        when {
            run.code ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)) { append(run.text) }
            run.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(run.text) }
            run.href != null -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(run.text) }
            else -> append(run.text)
        }
    }
}