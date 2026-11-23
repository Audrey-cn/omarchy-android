// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class InitialInstallTransactionTest {
    private fun fixture(test: (File, File, File, ImageSpec) -> Unit) {
        val root = Files.createTempDirectory("initial-install-test").toFile()
        try {
            val data = File(root, "data").apply { mkdir() }
            val stage = File(root, "staging").apply { mkdir() }
            val archive = File(root, "image.tar.zst").apply { writeText("verified-image") }
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val spec = ImageSpec("ubuntu24-test", "Test desktop", "arm64-v8a", 64, archive.length(), 4096, hash, "a".repeat(40), listOf("https://example.com/image.tar.zst"))
            test(data, stage, archive, spec)
        } finally { root.deleteRecursively() }
    }
    private fun unpack(destination: File) {
        File(destination, ".tiny.yaml").writeText("code: omarchy\n")
        File(destination, "usr/bin").mkdirs()
        File(destination, "usr/bin/bash").writeText("shell")
    }
    @Test fun verifiedImageIsPublishedOnlyAfterPreparation() = fixture { data, staging, archive, spec ->
        var prepared = false
        val tx = InitialInstallTransaction(data, staging)
        val result = tx.install(spec, archive, Long.MAX_VALUE, { _, dir ->
            assertFalse(File(data, "omarchy").exists()); unpack(dir)
        }, { dir ->
            assertFalse(File(data, "omarchy").exists()); File(dir, "prepared").writeText("yes"); prepared = true
        })
        assertTrue(prepared); assertEquals(File(data, "omarchy"), result)
        assertEquals("yes", File(result, "prepared").readText())
        assertNotNull(tx.publishedImage(spec))
    }
    @Test fun existingGuestIsNeverModified() = fixture { data, staging, archive, spec ->
        val guest = File(data, "omarchy").apply { mkdir() }
        val secret = File(guest, "history").apply { writeText("keep") }
        assertThrows(IOException::class.java) {
            InitialInstallTransaction(data, staging).install(spec, archive, Long.MAX_VALUE, { _, _ -> fail("must not extract") })
        }
        assertEquals("keep", secret.readText()); assertEquals(1, guest.list()!!.size)
    }
    @Test fun extractionFailureDoesNotPublishOrLoseArchive() = fixture { data, staging, archive, spec ->
        assertThrows(IOException::class.java) {
            InitialInstallTransaction(data, staging).install(spec, archive, Long.MAX_VALUE, { _, dir ->
                unpack(dir); throw IOException("tar failed")
            })
        }
        assertFalse(File(data, "omarchy").exists()); assertTrue(archive.isFile)
        assertTrue(staging.list()!!.isEmpty())
    }
    @Test fun preparationFailureAndMissingConfigDoNotPublish() = fixture { data, staging, archive, spec ->
        val tx = InitialInstallTransaction(data, staging)
        assertThrows(IOException::class.java) { tx.install(spec, archive, Long.MAX_VALUE, { _, _ -> }) }
        assertThrows(IOException::class.java) { tx.install(spec, archive, Long.MAX_VALUE, { _, d -> unpack(d) }, { throw IOException("bad identity") }) }
        assertFalse(File(data, "omarchy").exists())
    }
    @Test fun badHashAndInsufficientSpaceFailBeforeExtraction() = fixture { data, staging, archive, spec ->
        val tx = InitialInstallTransaction(data, staging)
        assertThrows(IOException::class.java) { tx.install(spec, archive, 1, { _, _ -> fail("space") }) }
        archive.appendText("tampered")
        assertThrows(IOException::class.java) { tx.install(spec, archive, Long.MAX_VALUE, { _, _ -> fail("hash") }) }
        assertFalse(File(data, "omarchy").exists())
    }
    @Test fun symlinkTargetAndStageAreRejected() = fixture { data, staging, archive, spec ->
        val outside = File(data.parentFile, "outside").apply { mkdir(); File(this, "sentinel").writeText("safe") }
        Files.createSymbolicLink(File(data, "omarchy").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { InitialInstallTransaction(data, staging).install(spec, archive, Long.MAX_VALUE, { _, _ -> fail() }) }
        assertEquals("safe", File(outside, "sentinel").readText())
        Files.delete(File(data, "omarchy").toPath()); staging.delete()
        Files.createSymbolicLink(staging.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { InitialInstallTransaction(data, staging).install(spec, archive, Long.MAX_VALUE, { _, _ -> fail() }) }
    }
    @Test fun completedMarkerCanBeRecoveredButUnknownGuestCannotBeAdopted() = fixture { data, staging, archive, spec ->
        val tx = InitialInstallTransaction(data, staging)
        assertNull(tx.publishedImage(spec))
        tx.install(spec, archive, Long.MAX_VALUE, { _, d -> unpack(d) })
        assertNotNull(tx.publishedImage(spec)); assertNull(tx.publishedImage(spec.copy(id = "another-image")))
    }
}
