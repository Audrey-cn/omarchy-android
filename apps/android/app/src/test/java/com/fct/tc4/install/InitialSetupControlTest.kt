// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class InitialSetupControlTest {
    @Test fun completeWithoutRegisteredGuestRoutesToOwnedPublicationRecovery() {
        assertEquals(InitialDesktopAction.RECOVER, initialDesktopAction(completed = true, registered = false))
        assertEquals(InitialDesktopAction.INSTALL, initialDesktopAction(completed = false, registered = false))
        assertEquals(InitialDesktopAction.LAUNCH, initialDesktopAction(completed = true, registered = true))
        assertEquals(InitialDesktopAction.LAUNCH, initialDesktopAction(completed = false, registered = true))
    }

    @Test fun registrationPersistsOneCompleteSnapshotAndPreservesExistingAutoLaunch() {
        val original = setOf("other-desktop")
        var persisted = emptyMap<String, Any>()
        var writes = 0
        InitialDesktopRegistration.commit(original, "other-desktop") { installed, firstLaunchDone, autoLaunch ->
            writes++
            persisted = mapOf("installed" to installed, "firstLaunchDone" to firstLaunchDone, "autoLaunch" to autoLaunch)
            true
        }
        assertEquals(1, writes)
        assertEquals(setOf("other-desktop", "omarchy"), persisted["installed"])
        assertEquals(true, persisted["firstLaunchDone"])
        assertEquals("other-desktop", persisted["autoLaunch"])
        assertEquals(setOf("other-desktop"), original)
    }

    @Test fun registrationDefaultsEmptyAutoLaunchToTheNewGuest() {
        var persisted = ""
        InitialDesktopRegistration.commit(emptySet(), "") { _, _, autoLaunch -> persisted = autoLaunch; true }
        assertEquals("omarchy", persisted)
    }

    private fun publishedFixture(test: (InitialInstallTransaction, File, ImageSpec) -> Unit) {
        val root = Files.createTempDirectory("initial-completion-test").toFile()
        try {
            val data = File(root, "data").apply { mkdir() }
            val stages = File(root, "staging").apply { mkdir() }
            val archive = File(root, "image.tar.zst").apply { writeText("verified-image") }
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
                .joinToString("") { "%02x".format(it) }
            val spec = ImageSpec("ubuntu24-test", "Test desktop", "arm64-v8a", 64,
                archive.length(), 4096, hash, "a".repeat(40), listOf("https://example.com/image.tar.zst"))
            val transaction = InitialInstallTransaction(data, stages)
            val guest = transaction.install(spec, archive, Long.MAX_VALUE, { _, stage ->
                File(stage, ".tiny.yaml").writeText("code: omarchy\n")
                File(stage, "usr/bin").mkdirs()
                File(stage, "usr/bin/bash").writeText("shell")
            })
            File(guest, "preserved-history").writeText("keep published guest")
            assertTrue(archive.delete())
            test(InitialInstallTransaction(data, stages), guest, spec)
        } finally { root.deleteRecursively() }
    }

    @Test fun persistedCompleteRecoversMissingRegistrationWithoutArchiveOrFreshInstallSpace() = publishedFixture { transaction, guest, spec ->
        val persistedComplete = true
        var installed = emptySet<String>()
        var completed = false
        assertEquals(InitialDesktopAction.RECOVER, initialDesktopAction(persistedComplete, "omarchy" in installed))
        val recovered = transaction.recoverOrCheckSpace(spec,
            { fail("A matching published guest must recover with only headroom free"); 512L * 1024 * 1024 },
            { fail("Recovery must not require the deleted archive or download"); 0L },
            {
                InitialDesktopRegistration.commit(installed, "") { next, _, _ -> installed = next; true }
            })
        if (recovered != null) completed = true
        assertEquals(guest, recovered)
        assertTrue(completed)
        assertEquals(setOf("omarchy"), installed)
        assertEquals(InitialDesktopAction.LAUNCH, initialDesktopAction(completed, "omarchy" in installed))
        assertEquals("keep published guest", File(guest, "preserved-history").readText())
    }

    @Test fun failedRegistrationCommitPreventsCompleteAndLeavesPublishedGuestRecoverable() = publishedFixture { transaction, guest, spec ->
        var completed = false
        var installed = emptySet<String>()
        assertThrows(IOException::class.java) {
            val recovered = transaction.recoverOrCheckSpace(spec,
                { fail("Registration failure must not fall back to full-install budgeting"); 512L * 1024 * 1024 },
                { fail("Registration failure must not request an archive"); 0L },
                { InitialDesktopRegistration.commit(installed, "") { _, _, _ -> false } })
            if (recovered != null) completed = true
        }
        assertFalse("COMPLETE must require acknowledged durable registration", completed)
        assertTrue(installed.isEmpty())
        assertEquals(InitialDesktopAction.RECOVER, initialDesktopAction(completed = true, registered = false))
        assertEquals(guest, transaction.publishedImage(spec))
        assertEquals("keep published guest", File(guest, "preserved-history").readText())
        transaction.recoverOrCheckSpace(spec, { fail("Retry must recover the same publication"); 0L },
            { fail("Retry must not download"); 0L },
            { InitialDesktopRegistration.commit(installed, "") { next, _, _ -> installed = next; true } })
        assertEquals(setOf("omarchy"), installed)
    }
}
