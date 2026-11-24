package com.fct.tc4.ui.misc

/** One foreground restoration; completion is latched to avoid a late loading flash. */
internal class DesktopLoadingState(val warmResume: Boolean, private val startedAt: Long) {
    enum class Appearance { HIDDEN, SPLASH, PILL }

    var ready = false
        private set

    fun observe(now: Long, displayReady: Boolean, desktopReady: Boolean): Appearance {
        ready = ready || (displayReady && (warmResume || desktopReady))
        return when {
            ready -> Appearance.HIDDEN
            !warmResume -> Appearance.SPLASH
            now - startedAt < 300 -> Appearance.HIDDEN
            else -> Appearance.PILL
        }
    }
}
