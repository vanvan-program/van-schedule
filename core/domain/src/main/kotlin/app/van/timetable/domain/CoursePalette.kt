// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.domain

/** Stable indices: the first five colors remain compatible with existing backups. */
object CoursePalette {
    val light =
        listOf(
            0xFFEEB2B2,
            0xFFF3B898,
            0xFFF5D78E,
            0xFF91DEB2,
            0xFFBBAAE1,
            0xFF9DC9F3,
            0xFF8DDAD6,
            0xFFF2B6D5,
            0xFFBCD790,
            0xFFAEBCE8,
            0xFFD4BBA3,
            0xFFBACBD6,
        )
    val dark =
        listOf(
            0xFFD6A0A7,
            0xFFDAAA8F,
            0xFFDCC183,
            0xFF98C9AC,
            0xFFB1A2CF,
            0xFF94B7D9,
            0xFF8CC3C1,
            0xFFD1A0BB,
            0xFFAABF89,
            0xFFA3AED0,
            0xFFC1AC97,
            0xFFA6B7C3,
        )
    val size
        get() = light.size

    fun argb(course: Course, isDark: Boolean = false): Long =
        course.customColor ?: (if (isDark) dark else light)[course.color]

    fun textArgb(background: Long): Long {
        fun linear(channel: Long): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        val luminance =
            0.2126 * linear((background shr 16) and 255) +
                0.7152 * linear((background shr 8) and 255) +
                0.0722 * linear(background and 255)
        return if (luminance > 0.179) 0xFF142036 else 0xFFFFFFFF
    }

    /**
     * 柔和卡片的文字色：保留课程色的色相，按卡片实际底色把明度调到够读。
     *
     * 色板本身是浅色填充（例如 `#91DEB2`），直接当文字色对比度不足；
     * 而换成品红/藏青这类固定文字色又会丢掉「一门课一个色」的辨识度。
     * 这里只动饱和度与明度、不动色相，所以绿卡仍是绿字、紫卡仍是紫字。
     *
     * @param fill 课程色（不含透明度）
     * @param cardArgb 卡片最终表面色：`fill` 按卡片不透明度压在页面底色上的结果
     */
    fun pastelTextArgb(fill: Long, cardArgb: Long, isDark: Boolean = false): Long {
        val (hue, saturation) = hueSaturationOf(fill)
        val targetSaturation =
            if (isDark) maxOf(saturation * 0.85, 0.45) else maxOf(saturation * 1.7, 0.55)
        val start = if (isDark) 0.92 else 0.44
        val step = if (isDark) 0.03 else -0.03
        var value = start
        // 逐步压暗（深色主题逐步提亮），直到与卡片底色达到 4.6:1
        repeat(20) {
            val candidate = hsvToArgb(hue, targetSaturation.coerceIn(0.0, 1.0), value.coerceIn(0.12, 0.98))
            if (contrastRatio(candidate, cardArgb) >= 4.6) return candidate
            value += step
        }
        return hsvToArgb(hue, targetSaturation.coerceIn(0.0, 1.0), if (isDark) 0.98 else 0.12)
    }

    private fun channel(color: Long, shift: Int): Double = ((color shr shift) and 255) / 255.0

    private fun linearize(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)

    private fun relativeLuminance(color: Long): Double =
        0.2126 * linearize(channel(color, 16)) +
            0.7152 * linearize(channel(color, 8)) +
            0.0722 * linearize(channel(color, 0))

    private fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hueSaturationOf(color: Long): Pair<Double, Double> {
        val r = channel(color, 16)
        val g = channel(color, 8)
        val b = channel(color, 0)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue =
            when {
                delta == 0.0 -> 0.0
                max == r -> 60.0 * (((g - b) / delta) % 6.0)
                max == g -> 60.0 * (((b - r) / delta) + 2.0)
                else -> 60.0 * (((r - g) / delta) + 4.0)
            }.let { if (it < 0) it + 360.0 else it }
        val saturation = if (max == 0.0) 0.0 else delta / max
        return hue to saturation
    }

    private fun hsvToArgb(hue: Double, saturation: Double, value: Double): Long {
        val c = value * saturation
        val h = (hue % 360.0) / 60.0
        val x = c * (1 - kotlin.math.abs((h % 2.0) - 1))
        val m = value - c
        val (r, g, b) =
            when (h.toInt()) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
        fun channel(v: Double): Long = ((v + m) * 255.0).toLong().coerceIn(0L, 255L)
        return (0xFFL shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
