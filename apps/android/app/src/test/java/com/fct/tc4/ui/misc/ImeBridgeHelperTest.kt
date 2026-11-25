// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBridgeHelperTest {
    @Test fun stripsEverySentinelWithoutChangingUserText() {
        assertEquals("你好 👩‍💻\n  ", ImeBridgeHelper.stripSentinel("\u200B你\u200B好 👩‍💻\n  \u200B"))
    }

    @Test fun stripsSentinelsFromCharSequencesAndHandlesMissingText() {
        assertEquals("ab", ImeBridgeHelper.stripSentinel(StringBuilder("a\u200Bb")))
        assertEquals("", ImeBridgeHelper.stripSentinel(null))
        assertEquals("", ImeBridgeHelper.stripSentinel("\u200B\u200B"))
    }

    @Test fun committedTextPreservesUnicodeAndWhitespace() {
        assertEquals(
            listOf(ImeBridgeHelper.Event.Text("  你好 👩‍💻  ")),
            ImeBridgeHelper.splitCommit("\u200B  你好 👩‍💻  \u200B")
        )
    }

    @Test fun committedNewlinesStayBetweenTheirTextSegments() {
        assertEquals(
            listOf(
                ImeBridgeHelper.Event.Text("first"),
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Text("第二行"),
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Text("last")
            ),
            ImeBridgeHelper.splitCommit("first\n第二行\nlast")
        )
    }

    @Test fun leadingTrailingAndConsecutiveNewlinesNeverProduceEmptyTextEvents() {
        assertEquals(
            listOf(
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Text("middle"),
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Newline
            ),
            ImeBridgeHelper.splitCommit("\u200B\n\u200B\nmiddle\n\u200B\n\u200B")
        )
    }

    @Test fun newlineOnlyCommitEmitsOneActionPerNewline() {
        assertEquals(
            listOf(ImeBridgeHelper.Event.Newline, ImeBridgeHelper.Event.Newline),
            ImeBridgeHelper.splitCommit("\n\n")
        )
    }

    @Test fun emptyOrSentinelOnlyCommitsProduceNoActions() {
        for (text in listOf(null, "", "\u200B", "\u200B\u200B")) {
            assertEquals(emptyList<ImeBridgeHelper.Event>(), ImeBridgeHelper.splitCommit(text))
        }
    }

    @Test fun sendFlushesRemainingTextAndNewlinesBeforeReturn() {
        assertEquals(
            listOf(
                ImeBridgeHelper.Event.Text("unfinished"),
                ImeBridgeHelper.Event.Newline,
                ImeBridgeHelper.Event.Text("结束"),
                ImeBridgeHelper.Event.Return
            ),
            ImeBridgeHelper.send("\u200Bunfinished\n结\u200B束\u200B")
        )
    }

    @Test fun sendPreservesTrailingNewlineBeforeReturn() {
        assertEquals(
            listOf(ImeBridgeHelper.Event.Text("line"), ImeBridgeHelper.Event.Newline, ImeBridgeHelper.Event.Return),
            ImeBridgeHelper.send("line\n")
        )
    }

    @Test fun emptySendStillReturnsWithoutSendingSentinelText() {
        for (text in listOf(null, "", "\u200B", "\u200B\u200B")) {
            assertEquals(listOf(ImeBridgeHelper.Event.Return), ImeBridgeHelper.send(text))
        }
    }

    @Test fun backspaceIsForwardedOnlyWhenNoUserTextRemains() {
        for (text in listOf(null, "", "\u200B", "\u200B\u200B")) {
            assertTrue(ImeBridgeHelper.shouldForwardBackspace(text, composing = false))
        }
        for (text in listOf("a", "\u200B你", " ", "\n", "👩‍💻")) {
            assertFalse(ImeBridgeHelper.shouldForwardBackspace(text, composing = false))
        }
    }

    @Test fun composingBackspaceNeverReachesTheDesktop() {
        for (text in listOf(null, "", "\u200B", "\u200B\u200B", "你")) {
            assertFalse(ImeBridgeHelper.shouldForwardBackspace(text, composing = true))
        }
    }
}
