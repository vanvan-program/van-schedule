// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 类型化路由与 0.4.0 字符串 route 的互转契约。
 *
 * [VanRoute.key] 同时是导航栈里保存的字符串、`SaveableStateHolder` 的键与旧存档的内容，
 * 所以「解析后再取 key」必须还原成原字符串，否则升级后返回栈与页面草稿会对不上。
 */
class VanRouteTest {

    private val allRoutes =
        listOf(
            VanRoute.Home,
            VanRoute.Notes,
            VanRoute.Import,
            VanRoute.DocumentReview,
            VanRoute.ImportPreview,
            VanRoute.School,
            VanRoute.ScheduleList,
            VanRoute.NewSchedule,
            VanRoute.Semester,
            VanRoute.Routines,
            VanRoute.Days,
            VanRoute.Holidays,
            // 0.5.16 起的四个外观三级页
            VanRoute.AppearanceTheme,
            VanRoute.AppearanceCard,
            VanRoute.AppearanceFont,
            VanRoute.AppearanceMotion,
            VanRoute.Appearance,
            VanRoute.Notifications,
            VanRoute.Band,
            VanRoute.Widgets,
            VanRoute.Backup,
            VanRoute.About,
            VanRoute.Version,
            VanRoute.Changelog,
            VanRoute.ScheduleSettings("s1"),
            VanRoute.Share("s1"),
            VanRoute.CourseDetail("c1"),
            VanRoute.CourseDetail("c1", "2026-03-02"),
            VanRoute.CourseDetail("c1", "2026-03-02", "ss1"),
            VanRoute.CourseDetail("c1", "2026-03-02", "ss1", "2026-03-02"),
            VanRoute.CourseEdit("c1"),
            VanRoute.CourseEdit("c1", "u1"),
            VanRoute.QuickAdd("c1", "lab"),
            VanRoute.QuickAdd("c1", "exam", "u1"),
            VanRoute.Adjust("ss1", "2026-03-02"),
            VanRoute.Unknown("nope:x"),
        )

    @Test
    fun `key survives a parse round trip`() {
        allRoutes.forEach { route ->
            assertEquals(route.key, vanRouteOf(route.key).key)
        }
    }

    @Test
    fun `typed values survive a parse round trip`() {
        allRoutes.forEach { route ->
            assertEquals(route, vanRouteOf(route.key))
        }
    }

    @Test
    fun `legacy strings map to the same destinations`() {
        assertEquals(VanRoute.Home, vanRouteOf("main"))
        assertEquals(VanRoute.ScheduleSettings("abc"), vanRouteOf("schedule:abc"))
        assertEquals(VanRoute.Share("abc"), vanRouteOf("share:abc"))
        assertEquals(VanRoute.CourseEdit("c1"), vanRouteOf("edit:c1"))
        assertEquals(VanRoute.CourseEdit("c1", "u1"), vanRouteOf("edit:c1:u1"))
        assertEquals(VanRoute.QuickAdd("c1", "lab"), vanRouteOf("quick:c1:lab"))
        assertEquals(VanRoute.Adjust("ss1", "2026-03-02"), vanRouteOf("adjust:ss1:2026-03-02"))
        assertEquals(
            VanRoute.CourseDetail("c1", "2026-03-02", "ss1", null),
            vanRouteOf("detail:c1:2026-03-02:ss1"),
        )
    }

    @Test
    fun `malformed strings fall back to unknown and keep the raw key`() {
        assertEquals(VanRoute.Unknown("schedule"), vanRouteOf("schedule"))
        assertEquals(VanRoute.Unknown("quick:c1"), vanRouteOf("quick:c1"))
        assertEquals(VanRoute.Unknown("adjust:ss1"), vanRouteOf("adjust:ss1"))
        assertEquals(VanRoute.Unknown("nonsense:1:2"), vanRouteOf("nonsense:1:2"))
        assertEquals(VanRoute.Unknown(""), vanRouteOf(""))
    }

    @Test
    fun `editor visits append one id and other targets are untouched`() {
        assertEquals("edit:c1:u1", normalizeRoute("edit:c1", "u1"))
        assertEquals("quick:c1:lab:u1", normalizeRoute("quick:c1:lab", "u1"))
        assertEquals("detail:c1", normalizeRoute("detail:c1", "u1"))
        assertEquals("appearance", normalizeRoute("appearance", "u1"))
    }
}