// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class ManagedCoreFilesTest {
    private val source = "a".repeat(40)
    private val path = "usr/local/bin/omarchy-fixture"

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fixture(test: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("omarchy-managed-test-").toFile()
        try {
            val guest = File(directory, "guest").apply { mkdir() }
            val state = File(directory, "state")
            test(guest, state)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun entry(name: String, value: String) = ManagedCoreFile(name, hash(value), 493)
    private fun sync(guest: File, state: File, payload: Map<String, String>, entries: List<ManagedCoreFile>? = null): ManagedCoreSyncResult {
        return ManagedCoreFiles(guest, state) { asset ->
            payload.getValue(asset.removePrefix("managed-core/files/")).byteInputStream()
        }.sync(source, entries ?: payload.map { entry(it.key, it.value) })
    }

    @Test fun newGuestAndRepeatPreservePersonalDataAndModes() = fixture { guest, state ->
        val personal = File(guest, "root/.hermes/config.json").apply { parentFile!!.mkdirs(); writeText("private") }
        val sway = File(guest, "root/.config/sway/config").apply { parentFile!!.mkdirs(); writeText("user sway") }
        val first = sync(guest, state, mapOf(path to "new"))
        assertEquals(1, first.updated)
        assertTrue(first.conflicts.isEmpty())
        assertEquals("new", File(guest, path).readText())
        assertTrue(File(guest, path).canExecute())
        assertEquals(0, sync(guest, state, mapOf(path to "new")).updated)
        assertEquals("private", personal.readText())
        assertEquals("user sway", sway.readText())
    }

    @Test fun legacyReplacementKeepsOneExactBackup() = fixture { guest, state ->
        File(guest, path).apply { parentFile!!.mkdirs(); writeText("legacy") }
        val first = sync(guest, state, mapOf(path to "new"))
        assertEquals("legacy", File(first.legacyBackup!!, path).readText())
        assertEquals("new", File(guest, path).readText())
        assertNull(sync(guest, state, mapOf(path to "newer")).legacyBackup)
        assertEquals("legacy", File(first.legacyBackup, path).readText())
    }

    @Test fun modifiedManagedFileBlocksWholeUpdateWithoutChangingReceipt() = fixture { guest, state ->
        sync(guest, state, mapOf(path to "old", "$path-two" to "old-two"))
        File(guest, path).writeText("user change")
        val result = sync(guest, state, mapOf(path to "new", "$path-two" to "new-two"))
        assertEquals(listOf(path), result.conflicts)
        assertEquals(0, result.updated)
        assertEquals("user change", File(guest, path).readText())
        assertEquals("old-two", File(guest, "$path-two").readText())
        File(guest, path).writeText("old")
        assertEquals(2, sync(guest, state, mapOf(path to "new", "$path-two" to "new-two")).updated)
    }

    @Test fun newlyManagedFileDoesNotOverwriteLaterUserFile() = fixture { guest, state ->
        sync(guest, state, mapOf(path to "old"))
        File(guest, "$path-two").writeText("user")
        val result = sync(guest, state, mapOf(path to "new", "$path-two" to "new-two"))
        assertEquals(listOf("$path-two"), result.conflicts)
        assertEquals("old", File(guest, path).readText())
        assertEquals("user", File(guest, "$path-two").readText())
    }

    @Test fun corruptLaterAssetIsRejectedBeforeAnyTargetChanges() = fixture { guest, state ->
        File(guest, path).apply { parentFile!!.mkdirs(); writeText("old") }
        val entries = listOf(entry(path, "new"), entry("$path-two", "expected"))
        assertThrows(IllegalArgumentException::class.java) {
            sync(guest, state, mapOf(path to "new", "$path-two" to "corrupt"), entries)
        }
        assertEquals("old", File(guest, path).readText())
        assertFalse(File(guest, "$path-two").exists())
    }

    @Test fun rejectsTraversalUnapprovedPathsDuplicatesAndInvalidModes() = fixture { guest, state ->
        for (invalid in listOf("/usr/local/bin/x", "usr/local/bin/../x", "usr/local/bin/nested/x",
                "root/.hermes/config", "opt/other/x", "opt/omarchy-node/../../x")) {
            assertThrows(IllegalArgumentException::class.java) { sync(guest, state, mapOf(invalid to "x")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            sync(guest, state, mapOf(path to "x"), listOf(entry(path, "x"), entry(path, "x")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sync(guest, state, mapOf(path to "x"), listOf(ManagedCoreFile(path, hash("x"), 511)))
        }
    }

    @Test fun rejectsParentAndTargetSymlinksWithoutEscapingGuest() = fixture { guest, state ->
        val outside = File(guest.parentFile, "outside").apply { mkdir() }
        val parent = File(guest, "usr/local").apply { mkdirs() }
        Files.createSymbolicLink(File(parent, "bin").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { sync(guest, state, mapOf(path to "new")) }
        assertFalse(File(outside, "omarchy-fixture").exists())
        File(parent, "bin").delete()
        File(parent, "bin").mkdir()
        val personal = File(outside, "personal").apply { writeText("private") }
        Files.createSymbolicLink(File(guest, path).toPath(), personal.toPath())
        assertThrows(IllegalArgumentException::class.java) { sync(guest, state, mapOf(path to "new")) }
        assertEquals("private", personal.readText())
    }

    @Test fun removesOnlyUnmodifiedRetiredManagedFiles() = fixture { guest, state ->
        sync(guest, state, mapOf(path to "old", "$path-two" to "retired"))
        assertEquals(1, sync(guest, state, mapOf(path to "new")).removed)
        assertFalse(File(guest, "$path-two").exists())
        sync(guest, state, mapOf(path to "new", "$path-two" to "retired"))
        File(guest, "$path-two").writeText("user changed retired")
        assertEquals(listOf("$path-two"), sync(guest, state, mapOf(path to "newer")).conflicts)
        assertEquals("user changed retired", File(guest, "$path-two").readText())
    }

    @Test fun interruptedPublishCanResumeFromKnownNewHash() = fixture { guest, state ->
        sync(guest, state, mapOf(path to "old", "$path-two" to "old-two"))
        // A previous process exited after one atomic rename but before its receipt.
        File(guest, path).writeText("new")
        val result = sync(guest, state, mapOf(path to "new", "$path-two" to "new-two"))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(1, result.updated)
        assertEquals("new-two", File(guest, "$path-two").readText())
    }

    @Test fun acceptsBothApprovedOptPrefixes() = fixture { guest, state ->
        val payload = mapOf("opt/omarchy-node/node/bin/node" to "node", "opt/omarchy-chromium128/browser/chrome" to "chrome")
        assertEquals(2, sync(guest, state, payload).updated)
        assertEquals("node", File(guest, "opt/omarchy-node/node/bin/node").readText())
    }

    @Test fun matchingContentsStillReceiveRequiredExecutableMode() = fixture { guest, state ->
        sync(guest, state, mapOf(path to "script"))
        Files.setPosixFilePermissions(File(guest, path).toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        assertEquals(1, sync(guest, state, mapOf(path to "script")).updated)
        assertTrue(File(guest, path).canExecute())
    }
}
