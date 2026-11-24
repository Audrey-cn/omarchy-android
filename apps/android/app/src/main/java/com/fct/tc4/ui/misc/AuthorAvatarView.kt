package com.fct.tc4.ui.misc

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import com.fct.tc4.R
import kotlin.math.roundToInt

/** Decorative author portrait shared by the desktop and Help attribution. */
class AuthorAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    init {
        val density = resources.displayMetrics.density
        val size = (26 * density).roundToInt()
        layoutParams = ViewGroup.LayoutParams(size, size)
        scaleType = ScaleType.CENTER_CROP
        setImageResource(R.drawable.omarchy_author_avatar)
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        foreground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(density.roundToInt().coerceAtLeast(1), Color.rgb(122, 162, 247))
        }
    }
}
