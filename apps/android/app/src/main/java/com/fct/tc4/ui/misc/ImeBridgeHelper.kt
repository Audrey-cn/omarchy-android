// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

internal object ImeBridgeHelper {
    const val SENTINEL = "\u200B"

    sealed interface Event {
        data class Text(val value: String) : Event
        /** A committed line break, delivered as Shift+Return. */
        data object Newline : Event
        /** The IME Send action, delivered as Return after any remaining text. */
        data object Return : Event
    }

    fun stripSentinel(text: CharSequence?): String =
        text?.toString().orEmpty().replace(SENTINEL, "")

    fun splitCommit(text: CharSequence?): List<Event> {
        val committed = stripSentinel(text)
        return buildList {
            var start = 0
            committed.forEachIndexed { index, character ->
                if (character == '\n') {
                    if (index > start) add(Event.Text(committed.substring(start, index)))
                    add(Event.Newline)
                    start = index + 1
                }
            }
            if (start < committed.length) add(Event.Text(committed.substring(start)))
        }
    }

    fun send(text: CharSequence?): List<Event> = splitCommit(text) + Event.Return

    fun shouldForwardBackspace(text: CharSequence?, composing: Boolean): Boolean =
        !composing && stripSentinel(text).isEmpty()
}
