// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

import java.text.Collator
import java.util.Locale

/**
 * 中文拼音 + 自然数字排序，参照 NexioSchedule `ChineseCollatorImpl` 的 ThreadLocal Collator 设计。
 */
object NaturalCourseSort {
    private val collators = ThreadLocal.withInitial {
        Collator.getInstance(Locale.CHINA).apply { strength = Collator.SECONDARY }
    }

    fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitEnd(a, ia)
                val endB = digitEnd(b, ib)
                val cmp = compareNumberChunks(a.substring(ia, endA), b.substring(ib, endB))
                if (cmp != 0) return cmp
                ia = endA
                ib = endB
            } else {
                val endA = textEnd(a, ia)
                val endB = textEnd(b, ib)
                val cmp = collators.get()!!.compare(a.substring(ia, endA), b.substring(ib, endB))
                if (cmp != 0) return cmp
                ia = endA
                ib = endB
            }
        }
        return a.length.compareTo(b.length)
    }

    private fun digitEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isDigit()) i++
        return i
    }

    private fun textEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length && !s[i].isDigit()) i++
        return i
    }

    private fun compareNumberChunks(a: String, b: String): Int {
        val trimA = a.trimStart('0').ifEmpty { "0" }
        val trimB = b.trimStart('0').ifEmpty { "0" }
        if (trimA.length != trimB.length) return trimA.length.compareTo(trimB.length)
        val cmp = trimA.compareTo(trimB)
        return if (cmp != 0) cmp else a.length.compareTo(b.length)
    }

    fun sortCourses(courses: List<Course>): List<Course> =
        courses.sortedWith { x, y ->
            val cmp = compare(x.name.trim(), y.name.trim())
            if (cmp != 0) cmp else x.id.compareTo(y.id)
        }
}
