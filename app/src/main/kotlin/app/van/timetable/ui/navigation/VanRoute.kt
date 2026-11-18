// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui.navigation

/**
 * 一次导航的目的地。
 *
 * 0.4.0 及之前用「字符串 route + 两处 when 列表」表达页面：分发在 VanApp，标题在 `title()`，
 * 参数靠 `route.split(':')` 手写下标。两份清单容易漂移，参数类型也不受约束。
 * 这里改成密封类型：参数在解析时一次性校验，标题随目的地一起定义，
 * 新增页面若漏接分发会编译失败（`when` 穷尽性检查）。
 *
 * [key] 刻意与旧字符串 route 完全相同，这样 `rememberSaveable` 的键在升级后依旧命中，
 * 已保存的页面草稿与滚动位置不会丢。
 */
sealed interface VanRoute {
    val key: String
    val title: String

    /** 首页：三个标签页共用，不显示顶栏。 */
    data object Home : VanRoute {
        override val key = "main"
        override val title = "van课表"
    }

    data object Notes : VanRoute {
        override val key = "notes"
        override val title = "课程备注"
    }

    data object Import : VanRoute {
        override val key = "import"
        override val title = "导入课表"
    }

    data object DocumentReview : VanRoute {
        override val key = "document"
        override val title = "识别结果"
    }

    data object ImportPreview : VanRoute {
        override val key = "preview"
        override val title = "导入预览"
    }

    data object School : VanRoute {
        override val key = "school"
        override val title = "教务导入"
    }

    data object ScheduleList : VanRoute {
        override val key = "schedules"
        override val title = "课表管理"
    }

    data object NewSchedule : VanRoute {
        override val key = "newSchedule"
        override val title = "新建课表"
    }

    /** 当前启用课表的学期设置；与 [ScheduleSettings] 同一个编辑器。 */
    data object Semester : VanRoute {
        override val key = "semester"
        override val title = "学期设置"
    }

    data object Routines : VanRoute {
        override val key = "routines"
        override val title = "作息管理"
    }

    /**
     * 「外观设置」的四个三级页。
     *
     * 外观设置原来是一整页 6 个面板、十多个滑杆与开关，要滚很久才找得到一项；
     * 现在二级页只留一张预览卡和四行入口，细节收进这四页。
     */
    data object AppearanceTheme : VanRoute {
        override val key = "appearanceTheme"
        override val title = "主题与壁纸"
    }

    data object AppearanceCard : VanRoute {
        override val key = "appearanceCard"
        override val title = "材质与卡片"
    }

    data object AppearanceFont : VanRoute {
        override val key = "appearanceFont"
        override val title = "字体与网格"
    }

    data object AppearanceMotion : VanRoute {
        override val key = "appearanceMotion"
        override val title = "视图与动效"
    }

    data object Days : VanRoute {
        override val key = "days"
        override val title = "调课管理"
    }

    data object Holidays : VanRoute {
        override val key = "holidays"
        override val title = "节假日设置"
    }

    data object Appearance : VanRoute {
        override val key = "appearance"
        override val title = "外观设置"
    }

    data object Notifications : VanRoute {
        override val key = "notifications"
        override val title = "提醒与手环"
    }

    data object Band : VanRoute {
        override val key = "band"
        override val title = "手环课表"
    }

    data object Widgets : VanRoute {
        override val key = "widgets"
        override val title = "桌面小组件"
    }

    data object Backup : VanRoute {
        override val key = "backup"
        override val title = "备份与恢复"
    }

    data object About : VanRoute {
        override val key = "about"
        override val title = "帮助与关于"
    }

    data object Version : VanRoute {
        override val key = "version"
        override val title = "关于版本"
    }

    data object Changelog : VanRoute {
        override val key = "changelog"
        override val title = "历史版本更新"
    }

    /** 指定课表的学期设置。 */
    data class ScheduleSettings(val scheduleId: String) : VanRoute {
        override val key get() = "schedule:$scheduleId"
        override val title = "学期设置"
    }

    /** 分享指定课表。 */
    data class Share(val scheduleId: String) : VanRoute {
        override val key get() = "share:$scheduleId"
        override val title = "分享课表"
    }

    /**
     * 课程详情。后三个参数来自课表里的具体一格（日期 / 时段 / 原定日期），
     * 从「课程」列表进入时只有 [courseId]。
     */
    data class CourseDetail(
        val courseId: String,
        val date: String? = null,
        val sessionId: String? = null,
        val originalDate: String? = null,
    ) : VanRoute {
        override val key get() = buildList {
            add("detail")
            add(courseId)
            date?.let(::add)
            sessionId?.let(::add)
            originalDate?.let(::add)
        }.joinToString(":")

        override val title = "课程详情"
    }

    /**
     * 编辑课程。[courseId] 为 `new` 时是新建。
     * [visitId] 让每次进入都是独立草稿，避免恢复出来的编辑器覆盖已保存的课程。
     */
    data class CourseEdit(val courseId: String, val visitId: String = "") : VanRoute {
        override val key get() = if (visitId.isEmpty()) "edit:$courseId" else "edit:$courseId:$visitId"
        override val title = "编辑课程"
    }

    /** 从某门正课派生实验课 / 期末考试。 */
    data class QuickAdd(
        val parentCourseId: String,
        val kind: String,
        val visitId: String = "",
    ) : VanRoute {
        override val key get() =
            if (visitId.isEmpty()) "quick:$parentCourseId:$kind"
            else "quick:$parentCourseId:$kind:$visitId"
        override val title = "快捷添加"
    }

    /** 单次调课：改一次 [sessionId] 在 [date] 当天的安排。 */
    data class Adjust(val sessionId: String, val date: String) : VanRoute {
        override val key get() = "adjust:$sessionId:$date"
        override val title = "单次调课"
    }

    /** 解析不出的目的地：保留原字符串，交给兜底页面处理。 */
    data class Unknown(val raw: String) : VanRoute {
        override val key get() = raw
        override val title = "van课表"
    }
}

/** 与 0.4.0 的字符串 route 一一对应，供 `nav("...")` 调用点与离线存档继续使用。 */
fun vanRouteOf(raw: String): VanRoute {
    val parts = raw.split(':')
    return when (parts[0]) {
        "main" -> VanRoute.Home
        "notes" -> VanRoute.Notes
        "import" -> VanRoute.Import
        "document" -> VanRoute.DocumentReview
        "preview" -> VanRoute.ImportPreview
        "school" -> VanRoute.School
        "schedules" -> VanRoute.ScheduleList
        "newSchedule" -> VanRoute.NewSchedule
        "semester" -> VanRoute.Semester
        "routines" -> VanRoute.Routines
        "appearanceTheme" -> VanRoute.AppearanceTheme
        "appearanceCard" -> VanRoute.AppearanceCard
        "appearanceFont" -> VanRoute.AppearanceFont
        "appearanceMotion" -> VanRoute.AppearanceMotion
        "days" -> VanRoute.Days
        "holidays" -> VanRoute.Holidays
        "appearance" -> VanRoute.Appearance
        "notifications" -> VanRoute.Notifications
        "band" -> VanRoute.Band
        "widgets" -> VanRoute.Widgets
        "backup" -> VanRoute.Backup
        "about" -> VanRoute.About
        "version" -> VanRoute.Version
        "changelog" -> VanRoute.Changelog
        "schedule" -> parts.getOrNull(1)?.let(VanRoute::ScheduleSettings) ?: VanRoute.Unknown(raw)
        "share" -> parts.getOrNull(1)?.let(VanRoute::Share) ?: VanRoute.Unknown(raw)
        "detail" ->
            parts.getOrNull(1)?.let {
                VanRoute.CourseDetail(it, parts.getOrNull(2), parts.getOrNull(3), parts.getOrNull(4))
            } ?: VanRoute.Unknown(raw)
        "edit" ->
            parts.getOrNull(1)?.let { VanRoute.CourseEdit(it, parts.getOrNull(2).orEmpty()) }
                ?: VanRoute.Unknown(raw)
        "quick" ->
            if (parts.size >= 3) VanRoute.QuickAdd(parts[1], parts[2], parts.getOrNull(3).orEmpty())
            else VanRoute.Unknown(raw)
        "adjust" ->
            if (parts.size >= 3) VanRoute.Adjust(parts[1], parts[2]) else VanRoute.Unknown(raw)
        else -> VanRoute.Unknown(raw)
    }
}

/** 编辑器类页面每次进入都追加一次性 id，草稿互不覆盖。 */
fun normalizeRoute(raw: String, visitId: String): String =
    if (raw.startsWith("edit:") || raw.startsWith("quick:")) "$raw:$visitId" else raw