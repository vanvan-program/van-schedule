// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/**
 * 导入时的课程累加器：同名课程复用同一门，新课程按调色板顺序取下一个颜色。
 *
 * 以前 CSV、教务、文档导入各自手写一遍同样的逻辑，颜色起点与注释还不完全一致。
 * 统一到这里之后，「同名复用 + 取色」只有一处实现。
 */
class CourseAccumulator(private val initial: List<Course> = emptyList()) {
    private val items = initial.toMutableList()

    val size: Int get() = items.size

    fun snapshot(): List<Course> = items.toList()

    fun names(): List<String> = items.map { it.name }

    fun get(name: String, note: String = ""): Course =
        items.firstOrNull { it.name == name }
            ?: Course(name = name, color = items.size % CoursePalette.size, note = note)
                .also(items::add)
}
