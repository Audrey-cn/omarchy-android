// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.ui.misc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBoundsTest {
    @Test fun collapsedRotationAndImeLayoutsNeverProduceEmptyRanges() {
        for (parent in listOf(0, 1, 24, 40, 56, 80, 1080, 2340)) {
            for (child in listOf(0, 12, 56, 220, 600)) {
                for (position in listOf(-100f, 0f, 100f, 3000f)) {
                    val x = OverlayBounds.horizontal(position, parent, child)
                    val y = OverlayBounds.vertical(position, parent, child, 40, 24)
                    assertTrue(x >= 0f)
                    assertTrue(x <= maxOf(0, parent - child))
                    assertTrue(y >= 40f)
                    assertTrue(y <= maxOf(40, parent - child - 24))
                }
            }
        }
    }

    @Test fun preservesPositionWhenSpaceIsAvailable() {
        assertEquals(650f, OverlayBounds.vertical(650f, 1080, 56, 40, 24), 0f)
        assertEquals(300f, OverlayBounds.horizontal(300f, 2340, 220), 0f)
    }

    @Test fun clampsOversizedDockAndOnboardingArrowDuringFirstLayout() {
        assertEquals(0f, OverlayBounds.horizontal(100f, 1, 220), 0f)
        assertEquals(40f, OverlayBounds.vertical(100f, 1, 56, 40, 24), 0f)
        assertEquals(60f, OverlayBounds.vertical(0f, 0, 0, 60, 60), 0f)
    }
}
