package com.fct.tc4.ui.misc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwayOnboardingIpcTest {
    @Test fun oldSocketCannotQualifyForANewX11Server() {
        assertNull(SwayOnboardingIpc.candidatePid("sway-ipc.0.141.sock", 125, 124))
        assertNull(SwayOnboardingIpc.candidatePid("another.sock", 125, 125))
        assertEquals(141, SwayOnboardingIpc.candidatePid("sway-ipc.0.141.sock", 125, 125))
    }

    @Test fun otherProcessOrUidCannotSatisfyAStaleSocketName() {
        assertFalse(SwayOnboardingIpc.matchesPeer(141, 10_500, 149, 10_500))
        assertFalse(SwayOnboardingIpc.matchesPeer(141, 10_500, 141, 10_501))
        assertTrue(SwayOnboardingIpc.matchesPeer(141, 10_500, 141, 10_500))
    }

    @Test fun connectedSwayMustReturnTheRequestedLiveIpcReply() {
        val request = ByteArrayOutputStream()
        val reply = ByteArrayInputStream(frame(size = 2, type = 3) + "[]".toByteArray())

        assertEquals("[]", SwayOnboardingIpc.request(reply, request, SwayOnboardingIpc.GET_OUTPUTS))
        assertArrayEquals(frame(size = 0, type = 3), request.toByteArray())
    }

    @Test fun deadAndMalformedIpcRepliesDoNotQualify() {
        val invalid = listOf(
            byteArrayOf(),
            frame(size = 2, type = 3),
            frame(size = 0, type = 3),
            frame(size = 1_048_577, type = 3),
            frame(size = 2, type = 1) + "[]".toByteArray(),
            frame(size = 2, type = 3).also { it[0] = 0 } + "[]".toByteArray()
        )
        for (reply in invalid) {
            assertThrows(IOException::class.java) {
                SwayOnboardingIpc.request(ByteArrayInputStream(reply), ByteArrayOutputStream(), 3)
            }
        }
    }

    @Test fun symmetricWorkspaceGapsAreNotAWaybarReservation() {
        assertNull(SwayOnboardingIpc.topReservation(outputY = 0, outputHeight = 720, workspaceY = 10, workspaceHeight = 700))
        assertNull(SwayOnboardingIpc.topReservation(outputY = 0, outputHeight = 720, workspaceY = 10, workspaceHeight = 660))
        assertEquals(28, SwayOnboardingIpc.topReservation(outputY = 0, outputHeight = 720, workspaceY = 38, workspaceHeight = 672))
    }

    @Test fun liveProcessIdentityUsesStatNameAndStartTimeAndRejectsZombies() {
        fun stat(name: String, state: String) =
            "8432 ($name) $state 1025 1025 1001 0 -1 4194304 10680 0 0 0 221 615 0 0 20 0 42 0 8310091 123456"

        assertEquals(8_310_091L, SwayOnboardingIpc.liveProcessStart(stat("waybar", "S"), "waybar"))
        assertNull(SwayOnboardingIpc.liveProcessStart(stat("waybar", "Z"), "waybar"))
        assertNull(SwayOnboardingIpc.liveProcessStart(stat("not waybar)", "S"), "waybar"))
    }

    @Test fun aSecondSwayOnDisplayOneCannotSupplyTheDisplayZeroDesktop() {
        val sessions = linkedMapOf(
            149 to environment("DISPLAY=:1", "XDG_RUNTIME_DIR=/tmp"),
            141 to environment("DISPLAY=:0", "XDG_RUNTIME_DIR=/tmp")
        )

        val matching = sessions.filterValues(SwayOnboardingIpc::matchesSwayEnvironment)
        assertEquals(setOf(141), matching.keys)
    }

    @Test fun waybarMustBelongToTheSelectedSwaySocketAndWaylandDisplay() {
        val socketName = "sway-ipc.0.141.sock"
        val otherSway = environment("SWAYSOCK=/tmp/sway-ipc.0.149.sock", "WAYLAND_DISPLAY=wayland-1", "XDG_RUNTIME_DIR=/tmp")
        val otherWayland = environment("SWAYSOCK=/tmp/sway-ipc.0.141.sock", "WAYLAND_DISPLAY=wayland-2", "XDG_RUNTIME_DIR=/tmp")
        val current = environment("XDG_RUNTIME_DIR=/tmp", "SWAYSOCK=/tmp/sway-ipc.0.141.sock", "WAYLAND_DISPLAY=wayland-1")

        assertFalse(SwayOnboardingIpc.matchesWaybarEnvironment(otherSway, socketName))
        assertFalse(SwayOnboardingIpc.matchesWaybarEnvironment(otherWayland, socketName))
        assertTrue(SwayOnboardingIpc.matchesWaybarEnvironment(current, socketName))
    }

    @Test fun duplicateOrOversizedEnvironmentCannotYieldAnAmbiguousMatch() {
        val path = "XDG_RUNTIME_DIR=/tmp"
        assertFalse(SwayOnboardingIpc.matchesSwayEnvironment(environment(path, "DISPLAY=:0", "DISPLAY=:1")))
        assertFalse(SwayOnboardingIpc.matchesSwayEnvironment(environment(path, "DISPLAY=:0", "IGNORE=" + "x".repeat(131_072))))
        assertTrue(SwayOnboardingIpc.matchesSwayEnvironment(environment(path, "UNRELATED=irrelevant", "DISPLAY=:0")))
    }

    @Test fun aTricklingFrameChecksTheSameDeadlineBeforeEveryRead() {
        var elapsed = 0
        val reply = object : ByteArrayInputStream(frame(size = 2, type = 3) + "[]".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                elapsed += 100
                return super.read(buffer, offset, minOf(length, 1))
            }
        }

        assertThrows(SocketTimeoutException::class.java) {
            SwayOnboardingIpc.request(reply, ByteArrayOutputStream(), 3) {
                if (elapsed >= 500) throw SocketTimeoutException()
            }
        }
        assertEquals(500, elapsed)
    }

    private fun environment(vararg fields: String) =
        ByteArrayInputStream(fields.joinToString("\u0000", postfix = "\u0000").toByteArray())

    private fun frame(size: Int, type: Int) = "i3-ipc".toByteArray() + byteArrayOf(
        size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte(),
        type.toByte(), (type shr 8).toByte(), (type shr 16).toByte(), (type shr 24).toByte()
    )
}
