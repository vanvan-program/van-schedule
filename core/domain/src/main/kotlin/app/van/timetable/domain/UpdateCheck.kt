// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/**
 * 一条可比较的发布信息。数据来自 GitHub 的公开发布页，不包含任何用户数据。
 *
 * @param version 规范化后的版本号，例如 0.3.3
 * @param tag 发布页上的原始标签，例如 v0.3.3
 * @param title 发布标题，缺省时使用“van课表 + 版本号”
 * @param notes 纯文本更新说明，可能为空（只拿到发布页跳转时没有说明）
 * @param publishedAt 发布时间，保留原始文本，例如 2026-11-11T05:08:12Z
 * @param pageUrl 发布页地址，用于打开下载与更新说明
 */
data class AppRelease(
    val version: String,
    val tag: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
)

/**
 * 版本号比较与发布信息解析。
 *
 * 这里不发起网络请求，抓取由 app 模块负责，因此规则可以在 JVM 单元测试中直接验证。
 */
object UpdateCheck {
    /**
     * 把发布标签或版本号文本规范成可比较的点分数字，例如 `v0.3.3` 转成 `0.3.3`。
     * 无法识别时返回 null，调用方按“没有拿到版本号”处理，不要猜测。
     */
    fun normalizeVersion(raw: String): String? {
        val core =
            raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.size !in 1..4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.joinToString(".")
    }

    /** 判断 [latest] 是否比 [current] 新；任一侧版本号无法解析时返回 false。 */
    fun isNewer(latest: String, current: String): Boolean {
        val left = versionParts(latest) ?: return false
        val right = versionParts(current) ?: return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 解析 GitHub 的发布动态（releases.atom），取第一条即最新发布。
     * 发布动态不需要登录，也没有 API 的按小时限流。
     */
    fun parseAtomRelease(xml: String): AppRelease? {
        val entry =
            Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?: return null
        // 只认发布动态里的 <link> 标签，避免更新说明正文里出现其他 releases/tag 链接时误判。
        val pageUrl =
            Regex("<link[^>]*href=\"([^\"]*/releases/tag/[^\"]+)\"")
                .find(entry)
                ?.groupValues
                ?.get(1)
                ?: return null
        val tag =
            pageUrl.substringAfterLast("/tag/").substringBefore('?').substringBefore('#').trimEnd('/')
        val version = normalizeVersion(tag) ?: return null
        val title =
            Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(entry)
                ?.groupValues
                ?.get(1)
                ?.let(::decodeEntities)
                ?.trim()
                .orEmpty()
        val publishedAt =
            Regex("<updated>(.*?)</updated>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(entry)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        val content =
            Regex("<content[^>]*>(.*?)</content>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(entry)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return AppRelease(
            version = version,
            tag = tag,
            title = title.ifBlank { "van课表 $version" },
            notes = htmlToText(content),
            publishedAt = publishedAt,
            pageUrl = pageUrl,
        )
    }

    /**
     * 从发布页跳转后的地址取出最新版本，例如
     * `https://github.com/vanvan-program/van-schedule/releases/tag/v0.3.3`。
     * 这条路径只有版本号和下载页，没有更新说明。
     */
    fun parseLatestPageUrl(url: String): AppRelease? {
        val marker = "/releases/tag/"
        val clean = url.trim().substringBefore('#')
        val index = clean.indexOf(marker)
        if (index < 0) return null
        val tag = clean.substring(index + marker.length).substringBefore('?').trimEnd('/')
        val version = normalizeVersion(tag) ?: return null
        val pageUrl = clean.substring(0, index + marker.length) + tag
        return AppRelease(version = version, tag = tag, title = "van课表 $version", notes = "", publishedAt = "", pageUrl = pageUrl)
    }

    /** 把发布说明的 HTML 转成可显示的纯文本，标题保留为 `## 标题`，列表项以 `• ` 开头。 */
    fun htmlToText(html: String): String {
        // 发布动态里的说明是转义过的 HTML，先还原实体再处理标签，否则正文中的代码标签会留在结果里。
        var text =
            decodeEntities(html)
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(p|div|h[1-6]|ul|ol|li|pre|blockquote|tr|table)>"), "\n")
                .replace(Regex("(?i)<h[1-6][^>]*>"), "## ")
                .replace(Regex("(?i)<li[^>]*>"), "• ")
                .replace(Regex("(?i)<img[^>]*alt=\"([^\"]*)\"[^>]*>"), "\$1")
                .replace(Regex("(?s)<[^>]+>"), "")
        return text.replace("\r\n", "\n")
            .split('\n')
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    /** 还原 XML/HTML 中转义的字符；无法识别时保留原文。 */
    fun decodeEntities(text: String): String {
        var result =
            Regex("&#(x?)([0-9a-fA-F]+);", RegexOption.IGNORE_CASE).replace(text) { match ->
                val digits = match.groupValues[2]
                val code = if (match.groupValues[1].isEmpty()) digits.toIntOrNull() else digits.toIntOrNull(16)
                code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
            }
        val named =
            mapOf(
                "lt" to "<",
                "gt" to ">",
                "amp" to "&",
                "quot" to "\"",
                "apos" to "'",
                "nbsp" to " ",
            )
        result =
            Regex("&([a-zA-Z]+);").replace(result) { match ->
                named[match.groupValues[1].lowercase()] ?: match.value
            }
        return result
    }

    private fun versionParts(version: String): List<Int>? =
        normalizeVersion(version)?.split('.')?.map(String::toInt)
}