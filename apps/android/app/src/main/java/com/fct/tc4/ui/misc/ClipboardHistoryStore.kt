// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

/** Most-recent-first text history; Android storage stays behind [persist]. */
class ClipboardHistoryStore(
    initialEntries: List<String> = emptyList(),
    private val persist: (List<String>) -> Unit = {}
) {
    private val history = initialEntries.asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_ENTRIES)
        .toMutableList()

    fun record(text: String) {
        if (text.isBlank()) return
        history.remove(text)
        history.add(0, text)
        if (history.size > MAX_ENTRIES) history.removeAt(history.lastIndex)
        persist(entries())
    }

    /** Return a detached snapshot so callers and persistence cannot change the history. */
    fun entries(): List<String> = history.toList()

    fun clear() {
        history.clear()
        persist(entries())
    }

    private companion object {
        const val MAX_ENTRIES = 30
    }
}
