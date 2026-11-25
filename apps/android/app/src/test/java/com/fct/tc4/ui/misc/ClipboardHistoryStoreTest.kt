// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryStoreTest {
    @Test fun recordsNewestTextFirst() {
        val store = ClipboardHistoryStore()

        store.record("first")
        store.record("second")

        assertEquals(listOf("second", "first"), store.entries())
    }

    @Test fun recordingExistingTextMovesItToFrontWithoutDuplicates() {
        val store = ClipboardHistoryStore(listOf("newest", "older", "oldest"))

        store.record("older")

        assertEquals(listOf("older", "newest", "oldest"), store.entries())
    }

    @Test fun comparesExactTextAndPreservesMeaningfulWhitespace() {
        val store = ClipboardHistoryStore()

        store.record("text")
        store.record(" text ")
        store.record("line one\nline two\n")

        assertEquals(listOf("line one\nline two\n", " text ", "text"), store.entries())
    }

    @Test fun blankTextDoesNotChangeOrPersistHistory() {
        val saved = mutableListOf<List<String>>()
        val store = ClipboardHistoryStore(listOf("existing"), persist = { saved.add(it) })

        for (blank in listOf("", " ", "\t\r\n", "\u2003")) store.record(blank)

        assertEquals(listOf("existing"), store.entries())
        assertTrue(saved.isEmpty())
    }

    @Test fun evictsOldestTextAfterThirtyEntries() {
        val store = ClipboardHistoryStore()

        for (number in 1..31) store.record("entry $number")

        val entries = store.entries()
        assertEquals(30, entries.size)
        assertEquals("entry 31", entries.first())
        assertEquals("entry 2", entries.last())
        assertTrue("entry 1" !in entries)
    }

    @Test fun restoringHistoryFiltersBlanksAndDuplicatesBeforeLimiting() {
        val initial = listOf("newest", "", "newest", "  ", " spaced ") +
            (1..35).map { "older $it" }
        val store = ClipboardHistoryStore(initial)

        val entries = store.entries()
        assertEquals(30, entries.size)
        assertEquals(listOf("newest", " spaced ", "older 1"), entries.take(3))
        assertEquals("older 28", entries.last())
    }

    @Test fun initialListAndReturnedSnapshotsDoNotShareStoreState() {
        val initial = mutableListOf("newest", "oldest")
        val store = ClipboardHistoryStore(initial)
        val snapshot = store.entries()

        initial.clear()
        store.record("later")

        assertEquals(listOf("newest", "oldest"), snapshot)
        assertEquals(listOf("later", "newest", "oldest"), store.entries())
        try {
            (snapshot as MutableList<String>).clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable snapshots also protect the store.
        }
        assertEquals(listOf("later", "newest", "oldest"), store.entries())
    }

    @Test fun persistedSnapshotsCanRestoreHistoryAndDoNotChangeLater() {
        val saved = mutableListOf<List<String>>()
        val store = ClipboardHistoryStore(persist = { saved.add(it) })

        store.record("first")
        store.record("second")
        val restored = ClipboardHistoryStore(saved.last())
        store.record("third")

        assertEquals(listOf("first"), saved[0])
        assertEquals(listOf("second", "first"), saved[1])
        assertEquals(listOf("second", "first"), restored.entries())
        assertEquals(listOf("third", "second", "first"), saved.last())
    }

    @Test fun persistenceCannotMutateStoreHistory() {
        val store = ClipboardHistoryStore(listOf("older"), persist = { snapshot ->
            try {
                (snapshot as MutableList<String>).clear()
            } catch (_: UnsupportedOperationException) {
                // Immutable persistence snapshots also protect the store.
            }
        })

        store.record("newer")

        assertEquals(listOf("newer", "older"), store.entries())
    }

    @Test fun clearPersistsEmptyHistoryAndAllowsNewEntries() {
        val saved = mutableListOf<List<String>>()
        val store = ClipboardHistoryStore(listOf("existing"), persist = { saved.add(it) })

        store.clear()

        assertTrue(store.entries().isEmpty())
        assertEquals(listOf(emptyList<String>()), saved)
        store.record("after clear")
        assertEquals(listOf("after clear"), store.entries())
        assertTrue(saved.first().isEmpty())
    }
}
