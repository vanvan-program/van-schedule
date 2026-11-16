package com.haooz.softglass.internal

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint

@Suppress("DEPRECATION")
internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
}
