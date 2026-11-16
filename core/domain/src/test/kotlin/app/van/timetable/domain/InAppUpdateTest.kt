// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import org.junit.Assert.*
import org.junit.Test

/** GitHub API 的发布 JSON 解析：应用内更新靠它拿到安装包直链与摘要。 */
class InAppUpdateTest {
    private val json = """
        {
          "tag_name": "v0.5.4",
          "name": "van课表 0.5.4 · 课间空档与休息横带",
          "body": "<p>修复横带</p><ul><li>只画午休晚休</li></ul>",
          "published_at": "2026-11-16T13:31:28Z",
          "html_url": "https://github.com/vanvan-program/van-schedule/releases/tag/v0.5.4",
          "assets": [
            {
              "name": "SHA256SUMS-0.5.4.txt",
              "browser_download_url": "https://example.com/SHA256SUMS-0.5.4.txt",
              "size": 193,
              "digest": null
            },
            {
              "name": "van-schedule-0.5.4-source.zip",
              "browser_download_url": "https://example.com/source.zip",
              "size": 7413335,
              "digest": "sha256:ce079dde0514634c62a4278cf15ca248c2c905efd6c59eeda30cba565957af51"
            },
            {
              "name": "van-schedule-0.5.4-arm64.apk",
              "browser_download_url": "https://example.com/van-schedule-0.5.4-arm64.apk",
              "size": 13595777,
              "digest": "sha256:8CF1A9596FF250212999B0BF21ED4F34819FCBE4B657B70ADF8C4F132729654C"
            }
          ]
        }
    """.trimIndent()

    @Test fun parsesVersionNotesAndArmApk() {
        val release = UpdateCheck.parseLatestReleaseJson(json)!!
        assertEquals("0.5.4", release.version)
        assertEquals("v0.5.4", release.tag)
        assertEquals("van课表 0.5.4 · 课间空档与休息横带", release.title)
        assertTrue(release.notes.contains("只画午休晚休"))
        assertEquals("2026-11-16T13:31:28Z", release.publishedAt)
        val apk = release.apk!!
        assertEquals("van-schedule-0.5.4-arm64.apk", apk.name)
        assertEquals("https://example.com/van-schedule-0.5.4-arm64.apk", apk.url)
        assertEquals("8cf1a9596ff250212999b0bf21ed4f34819fcbe4b657b70adf8c4f132729654c", apk.sha256)
        assertEquals(13595777L, apk.size)
    }

    @Test fun ignoresSourceZipAndChecksumFiles() {
        val release = UpdateCheck.parseLatestReleaseJson(json)!!
        assertFalse(release.apk!!.name.endsWith(".zip"))
        assertFalse(release.apk.name.contains("SHA256SUMS"))
    }

    @Test fun fallsBackToAnyApkWhenNoArmBuild() {
        val picked =
            UpdateCheck.pickApk(
                listOf(
                    ApkAsset("app-x86_64.apk", "https://example.com/x86.apk"),
                    ApkAsset("app-source.zip", "https://example.com/s.zip"),
                )
            )
        assertEquals("app-x86_64.apk", picked?.name)
    }

    @Test fun prefersArm64EvenWhenListedLast() {
        val picked =
            UpdateCheck.pickApk(
                listOf(
                    ApkAsset("app-universal.apk", "https://example.com/u.apk"),
                    ApkAsset("app-ARM64-v8a.apk", "https://example.com/a.apk"),
                )
            )
        assertEquals("app-ARM64-v8a.apk", picked?.name)
    }

    @Test fun missingAssetsLeaveApkNullSoUiCanFallBack() {
        val release = UpdateCheck.parseLatestReleaseJson(
            """{"tag_name":"v0.5.0","body":"说明","assets":[]}"""
        )!!
        assertNull(release.apk)
        assertEquals("0.5.0", release.version)
        // 没有 html_url 时按标签拼出发布页
        assertEquals("https://github.com/vanvan-program/van-schedule/releases/tag/v0.5.0", release.pageUrl)
    }

    @Test fun unparseableJsonReturnsNull() {
        assertNull(UpdateCheck.parseLatestReleaseJson("not json"))
        assertNull(UpdateCheck.parseLatestReleaseJson("""{"body":"没有标签"}"""))
        assertNull(UpdateCheck.parseLatestReleaseJson("""{"tag_name":"nightly"}"""))
    }

    @Test fun normalizesDigestFormats() {
        val hex = "8cf1a9596ff250212999b0bf21ed4f34819fcbe4b657b70adf8c4f132729654c"
        assertEquals(hex, UpdateCheck.normalizeSha256("sha256:$hex"))
        assertEquals(hex, UpdateCheck.normalizeSha256(hex.uppercase()))
        assertNull(UpdateCheck.normalizeSha256(null))
        assertNull(UpdateCheck.normalizeSha256("sha256:短了"))
        assertNull(UpdateCheck.normalizeSha256("md5:8cf1a9596ff250212999b0bf21ed4f34"))
    }

    private val apkUrl = "https://github.com/vanvan-program/van-schedule/releases/download/v0.5.5/van-schedule-0.5.5-arm64.apk"

    @Test fun downloadSourcesTryDirectFirstThenMirrors() {
        val sources = UpdateCheck.downloadSources(apkUrl, useMirror = true)
        assertEquals("直连必须排第一", apkUrl, sources.first())
        assertEquals("不应有重复地址", sources.size, sources.distinct().size)
        assertEquals(UpdateCheck.MIRROR_PREFIXES.size + 1, sources.size)
        sources.drop(1).forEach { source ->
            assertTrue("镜像地址应当以 https 开头并用原地址结尾：$source", source.startsWith("https://"))
            assertTrue("镜像地址应当只是给原地址加前缀：$source", source.endsWith(apkUrl))
            assertNotEquals("镜像地址不能等于原地址", apkUrl, source)
        }
    }

    @Test fun downloadSourcesHonoursOptOut() {
        assertEquals(listOf(apkUrl), UpdateCheck.downloadSources(apkUrl, useMirror = false))
    }

    @Test fun downloadSourcesHandlesBlankUrl() {
        assertTrue(UpdateCheck.downloadSources("   ", useMirror = true).isEmpty())
        assertTrue(UpdateCheck.downloadSources("", useMirror = false).isEmpty())
    }

    @Test fun mirrorPrefixesLookUsable() {
        assertTrue("至少要有一个备用镜像", UpdateCheck.MIRROR_PREFIXES.isNotEmpty())
        UpdateCheck.MIRROR_PREFIXES.forEach { prefix ->
            assertTrue(prefix, prefix.startsWith("https://") && prefix.endsWith("/"))
            assertFalse(prefix, prefix.contains(" "))
        }
    }
}
