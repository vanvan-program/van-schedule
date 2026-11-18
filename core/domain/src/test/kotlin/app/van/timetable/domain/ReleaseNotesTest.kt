// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * 发布说明是 Markdown，界面以前把它当纯文本直出，于是 `##`、`**`、`|` 全都露在屏幕上。
 * 这里锁定解析结果：结构要对，且**解析后的文字里不能再出现 Markdown 标记**。
 */
class ReleaseNotesTest {

    /** 每段拼回一行文字，方便断言「解析后还剩什么」。 */
    private fun texts(blocks: List<ReleaseBlock>): List<String> =
        blocks.flatMap { block ->
            when (block) {
                is ReleaseBlock.Heading -> listOf(block.runs.joinToString("") { it.text })
                is ReleaseBlock.Bullet -> listOf(block.runs.joinToString("") { it.text })
                is ReleaseBlock.Numbered -> listOf(block.runs.joinToString("") { it.text })
                is ReleaseBlock.Paragraph -> listOf(block.runs.joinToString("") { it.text })
                is ReleaseBlock.Table -> block.header + block.rows.flatten()
                ReleaseBlock.Divider -> emptyList()
            }
        }

    @Test fun headingsBulletsAndInlineFormattingAreParsed() {
        val blocks =
            ReleaseNotes.parse(
                """
                ## 改了什么

                这次修了 **误报**，涉及 `van-schedule-0.5.15-arm64.apk`。

                - 第一条
                - 第二条
                1. 有序一
                2. 有序二
                """.trimIndent()
            )
        assertEquals(
            listOf(
                "改了什么",
                "这次修了 误报，涉及 van-schedule-0.5.15-arm64.apk。",
                "第一条",
                "第二条",
                "有序一",
                "有序二",
            ),
            texts(blocks),
        )
        val heading = blocks.first() as ReleaseBlock.Heading
        assertEquals(2, heading.level)
        val paragraph = blocks[1] as ReleaseBlock.Paragraph
        assertEquals(listOf(false, true, false, true, false), paragraph.runs.map { it.bold || it.code })
        assertEquals("误报", paragraph.runs.first { it.bold }.text)
        assertEquals("van-schedule-0.5.15-arm64.apk", paragraph.runs.first { it.code }.text)
        assertEquals(listOf(1, 2), blocks.filterIsInstance<ReleaseBlock.Numbered>().map { it.number })
    }

    @Test fun tableHeaderAndRowsAreSeparatedAndSeparatorIsDropped() {
        val blocks =
            ReleaseNotes.parse(
                """
                | 附件 | 大小 | SHA-256 |
                |---|---:|---|
                | `a.apk` | 13,614,990 B | `cd5387ee…` |
                | `b.zip` | 7,449,204 B | `14f3ec25…` |
                """.trimIndent()
            )
        val table = blocks.single() as ReleaseBlock.Table
        assertEquals(listOf("附件", "大小", "SHA-256"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a.apk", "13,614,990 B", "cd5387ee…"), table.rows[0])
        // 分隔行不能变成数据行，也不能把反引号留在单元格里。
        assertFalse(table.rows.any { it.any { cell -> cell.contains("|") || cell.contains("`") } })
        assertFalse(table.rows.any { row -> row.all { it.contains("-") } })
    }

    @Test fun htmlConvertedBulletsAndHeadingsStillParse() {
        // 发布动态（releases.atom）这条兜底路径给的是 htmlToText 的结果，形如 `## 标题` + `• 项`。
        val blocks = ReleaseNotes.parse("## 下载与兼容性\n• 包名保持一致\n• 签名不一致时需卸载重装")
        assertEquals("下载与兼容性", (blocks[0] as ReleaseBlock.Heading).runs.single().text)
        assertEquals(
            listOf("包名保持一致", "签名不一致时需卸载重装"),
            blocks.filterIsInstance<ReleaseBlock.Bullet>().map { it.runs.single().text },
        )
    }

    @Test fun dividerAndQuoteAndCodeFenceAreHandled() {
        val blocks = ReleaseNotes.parse("---\n> 引用一行\n```\nval a = 1\n```")
        assertTrue(blocks[0] is ReleaseBlock.Divider)
        assertEquals("引用一行", (blocks[1] as ReleaseBlock.Paragraph).runs.single().text)
        val code = (blocks[2] as ReleaseBlock.Paragraph).runs.single()
        assertTrue(code.code)
        assertEquals("val a = 1", code.text)
    }

    @Test fun linksKeepTheirText() {
        val runs = ReleaseNotes.inline("见 [发布页](https://example.com/x) 说明")
        assertEquals("见 发布页 说明", runs.joinToString("") { it.text })
        assertEquals("https://example.com/x", runs.first { it.href != null }.href)
    }

    @Test fun normalizeTrimsTrailingSpaceAndCollapsesBlankLines() {
        assertEquals("a\n\nb", ReleaseNotes.normalize("a   \r\n\r\n\r\n\r\nb\t\n"))
    }

    /** 用 0.5.15 的真实发布正文回归：解析后不能再看到 Markdown 标记。 */
    @Test fun realReleaseBodyHasNoMarkdownMarkersLeft() {
        val body =
            """
            van课表 0.5.15（versionCode 65）。修复「这次发布没有附带安装包」的误报。

            ## 问题现象

            点「检查更新」→「立即更新」，提示「这次发布没有附带安装包」。

            ## 修复

            **不降低安全性**：下载后仍强制比对签名证书。

            | 附件 | 大小 | SHA-256 |
            |---|---:|---|
            | `van-schedule-0.5.15-arm64.apk` | 13,614,610 B | `0413f604…` |
            | `van-schedule-0.5.15-source.zip` | 7,447,006 B | `94e62523…` |

            ## 安装

            装 `van-schedule-0.5.15-arm64.apk` 覆盖即可。
            """.trimIndent()
        val blocks = ReleaseNotes.parse(body)
        val all = texts(blocks).joinToString("\n")
        assertFalse("不应残留井号标题", all.contains("##"))
        assertFalse("不应残留加粗标记", all.contains("**"))
        assertFalse("不应残留反引号", all.contains("`"))
        assertFalse("不应残留表格竖线", all.contains("|"))
        assertTrue(all.contains("问题现象"))
        assertTrue(all.contains("不降低安全性"))
        assertEquals(1, blocks.filterIsInstance<ReleaseBlock.Table>().size)
        assertTrue(blocks.count { it is ReleaseBlock.Heading } == 3)
    }
}