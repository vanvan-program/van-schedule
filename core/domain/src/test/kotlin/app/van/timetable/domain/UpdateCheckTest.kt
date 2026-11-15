package app.van.timetable.domain

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckTest {
    @Test fun normalizeVersionAcceptsReleaseTags() {
        assertEquals("0.3.3", UpdateCheck.normalizeVersion("v0.3.3"))
        assertEquals("0.3.3", UpdateCheck.normalizeVersion(" V0.3.3 "))
        assertEquals("0.3.3", UpdateCheck.normalizeVersion("0.3.3-beta.1"))
        assertEquals("0.3.3", UpdateCheck.normalizeVersion("0.3.3+build.7"))
        assertEquals("1", UpdateCheck.normalizeVersion("1"))
        assertEquals("0.3", UpdateCheck.normalizeVersion("0.3"))
    }

    @Test fun normalizeVersionRejectsUnknownText() {
        assertNull(UpdateCheck.normalizeVersion(""))
        assertNull(UpdateCheck.normalizeVersion("release-0.3.3"))
        assertNull(UpdateCheck.normalizeVersion("0.3."))
        assertNull(UpdateCheck.normalizeVersion("0..3"))
        assertNull(UpdateCheck.normalizeVersion("0.3.abc"))
    }

    @Test fun isNewerComparesNumericPartsNotText() {
        assertTrue(UpdateCheck.isNewer("0.3.3", "0.3.2"))
        assertTrue(UpdateCheck.isNewer("0.3.10", "0.3.9"))
        assertTrue(UpdateCheck.isNewer("0.4", "0.3.9"))
        assertTrue(UpdateCheck.isNewer("0.3.2.1", "0.3.2"))
        assertTrue(UpdateCheck.isNewer("v0.3.3", "0.3.2"))
        assertFalse(UpdateCheck.isNewer("0.3.2", "0.3.2"))
        assertTrue(UpdateCheck.isNewer("0.2.10", "0.2.9"))
        assertFalse(UpdateCheck.isNewer("0.3.2", "0.3.3"))
    }

    @Test fun isNewerDoesNotGuessWhenVersionMissing() {
        assertFalse(UpdateCheck.isNewer("nightly", "0.3.2"))
        assertFalse(UpdateCheck.isNewer("0.3.3", ""))
        assertFalse(UpdateCheck.isNewer("", ""))
    }

    @Test fun parseAtomReleaseReadsNewestEntry() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from van-schedule</title>
              <entry>
                <id>tag:github.com,2008:Repository/1/v0.3.3</id>
                <updated>2026-11-16T02:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/vanvan-program/van-schedule/releases/tag/v0.3.3"/>
                <title>van课表 0.3.3 · 版本信息与检查更新</title>
                <content type="html">&lt;h1&gt;van课表 0.3.3&lt;/h1&gt;
            &lt;p&gt;“我的”新增“关于版本”，显示当前版本号与包名。&lt;/p&gt;
            &lt;ul&gt;
            &lt;li&gt;检查更新只读取版本号与更新说明。&lt;/li&gt;
            &lt;li&gt;课表与账号数据不会被上传。&lt;/li&gt;
            &lt;/ul&gt;</content>
              </entry>
              <entry>
                <title>van课表 0.3.2 · 小组件添加反馈</title>
                <link rel="alternate" type="text/html" href="https://github.com/vanvan-program/van-schedule/releases/tag/v0.3.2"/>
              </entry>
            </feed>
            """.trimIndent()
        val release = UpdateCheck.parseAtomRelease(xml)!!
        assertEquals("0.3.3", release.version)
        assertEquals("v0.3.3", release.tag)
        assertEquals("van课表 0.3.3 · 版本信息与检查更新", release.title)
        assertEquals("2026-11-16T02:00:00Z", release.publishedAt)
        assertEquals("https://github.com/vanvan-program/van-schedule/releases/tag/v0.3.3", release.pageUrl)
        val notes = release.notes.lines()
        assertEquals("## van课表 0.3.3", notes.first())
        assertTrue(notes.contains("“我的”新增“关于版本”，显示当前版本号与包名。"))
        assertTrue(notes.contains("• 检查更新只读取版本号与更新说明。"))
        assertTrue(notes.contains("• 课表与账号数据不会被上传。"))
        assertFalse(release.notes.contains("<"))
        assertFalse(release.notes.contains("小组件添加反馈"))
    }

    @Test fun parseAtomReleaseRejectsFeedWithoutReleases() {
        assertNull(UpdateCheck.parseAtomRelease("<feed><title>Release notes from x</title></feed>"))
        assertNull(UpdateCheck.parseAtomRelease(""))
    }

    @Test fun parseAtomReleaseIgnoresReleaseLinksInsideNotes() {
        // 更新说明里可能引用旧版本的发布页；只能取 <link> 标签里的发布地址。
        val xml =
            """
            <feed><entry>
              <content type="html">&lt;p&gt;&lt;a href="https://github.com/o/r/releases/tag/v0.1.0"&gt;旧版本&lt;/a&gt;&lt;/p&gt;</content>
              <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/v1.2.3"/>
              <title>van课表 1.2.3</title>
              <updated>2026-01-02T03:04:05Z</updated>
            </entry></feed>
            """.trimIndent()
        val release = UpdateCheck.parseAtomRelease(xml)!!
        assertEquals("1.2.3", release.version)
        assertEquals("https://github.com/o/r/releases/tag/v1.2.3", release.pageUrl)
        assertTrue(release.notes.contains("旧版本"))
    }

    @Test fun parseLatestPageUrlOnlyAcceptsReleaseTags() {
        val release =
            UpdateCheck.parseLatestPageUrl(
                "https://github.com/vanvan-program/van-schedule/releases/tag/v0.4?expanded=true"
            )!!
        assertEquals("0.4", release.version)
        assertEquals("v0.4", release.tag)
        assertEquals("https://github.com/vanvan-program/van-schedule/releases/tag/v0.4", release.pageUrl)
        assertEquals("", release.notes)
        assertEquals("van课表 0.4", release.title)
        assertNull(
            UpdateCheck.parseLatestPageUrl("https://github.com/vanvan-program/van-schedule/releases")
        )
        assertNull(
            UpdateCheck.parseLatestPageUrl("https://github.com/vanvan-program/van-schedule/releases/tag/latest")
        )
    }

    @Test fun htmlToTextKeepsHeadingsListsAndDecodedCharacters() {
        val text =
            UpdateCheck.htmlToText(
                "<h2>下载与兼容性</h2><p>手机安装 &lt;code&gt;van-schedule-0.3.3-arm64.apk&lt;/code&gt;，仅支持 ARM64。</p>" +
                    "<ul><li>包名保持一致</li><li>签名不一致时需卸载重装</li></ul>" +
                    "<script>alert(1)</script>"
            )
        assertEquals(
            listOf(
                "## 下载与兼容性",
                "手机安装 van-schedule-0.3.3-arm64.apk，仅支持 ARM64。",
                "• 包名保持一致",
                "• 签名不一致时需卸载重装",
            ),
            text.lines(),
        )
    }

    @Test fun decodeEntitiesHandlesNamedAndNumericCodes() {
        assertEquals("<b>&“引号”</b>", UpdateCheck.decodeEntities("&lt;b&gt;&amp;“引号”&lt;/b&gt;"))
        assertEquals("A B", UpdateCheck.decodeEntities("A&#32;B"))
        assertEquals("课", UpdateCheck.decodeEntities("&#35838;"))
        assertEquals("课", UpdateCheck.decodeEntities("&#x8BFE;"))
        assertEquals("&unknown;", UpdateCheck.decodeEntities("&unknown;"))
    }
}