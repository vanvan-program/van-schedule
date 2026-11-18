// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/**
 * 更新说明里的一段内容。
 *
 * GitHub 的发布说明是 Markdown（标题、列表框、表格、行内加粗与代码），而应用以前把整段文本
 * 塞进一个 `Text`，于是界面上直接看到 `##`、`**` 和 `|` 竖线。这里先解析成结构化段落，
 * 再由界面按段渲染。
 */
sealed interface ReleaseBlock {
    /** `## 标题`，[level] 即井号个数（1..6）。 */
    data class Heading(val level: Int, val runs: List<InlineRun>) : ReleaseBlock

    /** `- 项`，[depth] 是缩进层级（0 起）。 */
    data class Bullet(val depth: Int, val runs: List<InlineRun>) : ReleaseBlock

    /** `1. 项`。 */
    data class Numbered(val number: Int, val runs: List<InlineRun>) : ReleaseBlock

    /** 普通段落。 */
    data class Paragraph(val runs: List<InlineRun>) : ReleaseBlock

    /** Markdown 表格；手机窄屏上按「表头一行 + 每行一行」渲染。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : ReleaseBlock

    /** `---` 分隔线。 */
    data object Divider : ReleaseBlock
}

/** 段落里的一段文字，可加粗、等宽（行内代码）或带链接。 */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
)

/**
 * 一个够用的 Markdown 子集解析器：标题、有序 / 无序列表、引用、表格、分隔线、代码围栏，
 * 以及行内的 `**加粗**`、`` `代码` ``、`[文字](链接)`、`~~删除线~~`。
 *
 * 不追求完整 CommonMark：发布说明是用来读的，未支持的行按普通段落显示，不会丢内容。
 */
object ReleaseNotes {

    /** 统一换行、压缩行内空白与多余空行；发布 JSON 里的正文与发布动态的文本都能过这一步。 */
    fun normalize(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    fun parse(raw: String): List<ReleaseBlock> {
        val lines = normalize(raw).split('\n')
        val blocks = mutableListOf<ReleaseBlock>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() -> i++

                trimmed.startsWith("```") -> {
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code += lines[i]
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += ReleaseBlock.Paragraph(listOf(InlineRun(code.joinToString("\n"), code = true)))
                }

                HORIZONTAL_RULE.matches(trimmed) -> {
                    blocks += ReleaseBlock.Divider
                    i++
                }

                HEADING.matches(trimmed) -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += ReleaseBlock.Heading(level, inline(trimmed.drop(level).trim()))
                    i++
                }

                isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()) -> {
                    val header = cellsOf(trimmed).map(::plainInline)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && isTableRow(lines[i].trim())) {
                        rows += cellsOf(lines[i].trim()).map(::plainInline)
                        i++
                    }
                    blocks += ReleaseBlock.Table(header, rows)
                }

                BULLET.matches(trimmed) -> {
                    val depth = (lines[i].takeWhile { it == ' ' || it == '\t' }.length / 2).coerceIn(0, 4)
                    blocks += ReleaseBlock.Bullet(depth, inline(trimmed.drop(1).trim()))
                    i++
                }

                NUMBERED.matches(trimmed) -> {
                    val number = trimmed.takeWhile(Char::isDigit).toIntOrNull() ?: 1
                    blocks += ReleaseBlock.Numbered(number, inline(trimmed.dropWhile { it.isDigit() }.drop(1).trim()))
                    i++
                }

                QUOTE.matches(trimmed) -> {
                    blocks += ReleaseBlock.Paragraph(inline(trimmed.drop(1).trim()))
                    i++
                }

                else -> {
                    val buffer = mutableListOf<String>()
                    while (i < lines.size) {
                        val text = lines[i].trim()
                        if (text.isEmpty()) break
                        if (buffer.isNotEmpty() && startsBlock(text, lines, i)) break
                        buffer += text
                        i++
                    }
                    if (buffer.isEmpty()) i++
                    blocks += ReleaseBlock.Paragraph(inline(buffer.joinToString(" ")))
                }
            }
        }
        return blocks
    }

    /** 行内标记解析成若干段。 */
    fun inline(raw: String): List<InlineRun> {
        val runs = mutableListOf<InlineRun>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                runs += InlineRun(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < raw.length) {
            val rest = raw.length - i
            when {
                rest >= 2 && raw.startsWith("**", i) -> {
                    val end = raw.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush(); runs += InlineRun(raw.substring(i + 2, end), bold = true); i = end + 2
                    } else {
                        plain.append(raw[i]); i++
                    }
                }
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush(); runs += InlineRun(raw.substring(i + 1, end), code = true); i = end + 1
                    } else {
                        plain.append(raw[i]); i++
                    }
                }
                rest >= 2 && raw.startsWith("~~", i) -> {
                    val end = raw.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        plain.append(raw.substring(i + 2, end)); i = end + 2
                    } else {
                        plain.append(raw[i]); i++
                    }
                }
                raw[i] == '[' -> {
                    val close = raw.indexOf(']', i)
                    val open = if (close > i && close + 1 < raw.length && raw[close + 1] == '(') {
                        raw.indexOf(')', close + 2)
                    } else -1
                    if (open > close) {
                        flush()
                        runs += InlineRun(raw.substring(i + 1, close), href = raw.substring(close + 2, open))
                        i = open + 1
                    } else {
                        plain.append(raw[i]); i++
                    }
                }
                raw[i] == '\\' && i + 1 < raw.length -> {
                    plain.append(raw[i + 1]); i += 2
                }
                else -> {
                    plain.append(raw[i]); i++
                }
            }
        }
        flush()
        return runs
    }

    /** 去掉行内标记只留文字，用于表格单元格。 */
    fun plainInline(raw: String): String =
        inline(raw).joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()

    private fun startsBlock(text: String, lines: List<String>, index: Int): Boolean =
        text.startsWith("```") ||
            HORIZONTAL_RULE.matches(text) ||
            HEADING.matches(text) ||
            BULLET.matches(text) ||
            NUMBERED.matches(text) ||
            QUOTE.matches(text) ||
            (isTableRow(text) && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    private fun isTableRow(text: String): Boolean = text.length > 1 && text.startsWith("|")

    private fun isTableSeparator(text: String): Boolean {
        if (!text.startsWith("|")) return false
        val cells = cellsOf(text)
        return cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it) }
    }

    private fun cellsOf(row: String): List<String> =
        row.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private val HORIZONTAL_RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val HEADING = Regex("^#{1,6}\\s+\\S.*$")
    private val BULLET = Regex("^([-*+]|\u2022)\\s+\\S.*$")
    private val NUMBERED = Regex("^\\d{1,3}[.)]\\s+\\S.*$")
    private val QUOTE = Regex("^>\\s*\\S.*$")
    private val SEPARATOR_CELL = Regex("^:?-{1,}:?$")
}