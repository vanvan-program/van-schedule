// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

data class CourseGroup(val primary: Course, val members: List<Course>)

/** Group only saved relationships, never similar names. Orphans and cycles stay visible. */
fun Schedule.courseGroups(): List<CourseGroup> {
    val byId = courses.associateBy { it.id }
    fun root(course: Course): Course {
        var current = course
        val visited = mutableSetOf<String>()
        while (visited.add(current.id)) {
            val parent = byId[current.parentCourseId] ?: return current
            current = parent
        }
        return course
    }
    return courses.groupBy { root(it).id }.map { (id, members) ->
        val primary = byId.getValue(id)
        CourseGroup(primary, listOf(primary) + members.filterNot { it.id == id })
    }
}

fun Course.groupSectionLabel(): String = when (kind) {
    "实验课" -> "实验课"
    "期末考试" -> "期末考试"
    else -> if (parentCourseId == null) "正课" else kind
}
