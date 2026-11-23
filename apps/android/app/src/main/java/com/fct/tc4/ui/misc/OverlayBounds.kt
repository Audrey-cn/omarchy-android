// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import kotlin.math.max

/** Layout passes during rotation/IME transitions can be smaller than the overlay itself. */
internal object OverlayBounds {
    fun horizontal(value: Float, parentWidth: Int, childWidth: Int): Float =
        value.coerceIn(0f, max(0f, (parentWidth - childWidth).toFloat()))

    fun vertical(value: Float, parentHeight: Int, childHeight: Int, top: Int, bottom: Int): Float {
        val minY = top.toFloat()
        val maxY = max(minY, (parentHeight - childHeight - bottom).toFloat())
        return value.coerceIn(minY, maxY)
    }
}
