package com.fct.tc4.ui.misc

import org.junit.Assert.*
import org.junit.Test

class DesktopProcessSnapshotTest {
    @Test fun changedGroupsDetachedChildrenAndTracedDaemonsBelongToTheDesktop() {
        val processes = listOf(
            DesktopProcessSnapshot(10, 1, 10, 10, 100),
            DesktopProcessSnapshot(11, 10, 11, 10, 110),
            DesktopProcessSnapshot(12, 11, 12, 12, 120),
            DesktopProcessSnapshot(13, 1, 13, 13, 130, tracerPid = 10),
            DesktopProcessSnapshot(14, 1, 14, 10, 140),
            DesktopProcessSnapshot(99, 1, 99, 99, 990)
        )
        assertEquals(setOf(10, 11, 12, 13, 14), desktopProcesses(10, processes).map { it.pid }.toSet())
        assertTrue(desktopProcesses(0, processes).isEmpty())
        assertTrue(desktopProcesses(-1, processes).isEmpty())
    }

    @Test fun processNamesWithSpacesAndParenthesesDoNotCorruptOwnershipOrIdentity() {
        val line = "10 (guest (shell)) S 2 10 10 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 12345 0"
        assertEquals(DesktopProcessSnapshot(10, 2, 10, 10, 12345), parseDesktopProcessStat(line))
        assertNull(parseDesktopProcessStat("10 (missing fields) S 1"))
        assertNull(parseDesktopProcessStat("bad input"))
    }

    @Test fun fallbackKeepsDetachedOriginalChildrenButNeverKillsAReusedPid() {
        val original = listOf(
            DesktopProcessSnapshot(10, 1, 10, 10, 100),
            DesktopProcessSnapshot(11, 10, 11, 10, 110),
            DesktopProcessSnapshot(12, 10, 12, 10, 120)
        )
        val afterGrace = listOf(
            // The tracer already exited; the uncooperative daemon is reparented.
            DesktopProcessSnapshot(11, 1, 11, 11, 110),
            // Another desktop has reused an old numeric PID.
            DesktopProcessSnapshot(12, 1, 12, 12, 999)
        )
        assertEquals(listOf(11), survivingDesktopProcesses(original, afterGrace).map { it.pid })
    }
}
