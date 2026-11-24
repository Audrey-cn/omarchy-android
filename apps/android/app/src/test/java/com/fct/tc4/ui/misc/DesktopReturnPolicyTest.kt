package com.fct.tc4.ui.misc

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopReturnPolicyTest {
    @Test fun onlyAnOrdinaryVisitToAnActiveDesktopIsForwarded() {
        for (active in listOf(false, true)) {
            for (management in listOf(false, true)) {
                for (pending in listOf(false, true)) {
                    assertEquals(active && !management && !pending,
                        shouldReturnToX11(active, management, pending))
                }
            }
        }
    }
}
