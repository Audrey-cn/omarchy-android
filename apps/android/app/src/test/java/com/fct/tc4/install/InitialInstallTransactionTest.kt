// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.InputStream
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
    @Test fun interruptedOwnedStageIsReclaimedBeforeRetryExtraction() = fixture { data, staging, archive, spec ->
        val old = File(staging, "image-00000000-0000-4000-8000-000000000001").apply { mkdir() }
        File(old, "large-unfinished-image").writeText("old extraction")
        File(staging, old.name + ".owner").writeText("initial-staging-v1\t${old.name}\n")
        val tx = InitialInstallTransaction(data, staging)
        tx.install(spec, archive, Long.MAX_VALUE, { _, destination ->
            assertFalse("Interrupted extraction must be reclaimed before allocating another image", old.exists())
            unpack(destination)
        })
        assertTrue(archive.isFile)
    }
    @Test fun interruptedStageIsReclaimedBeforeTheFreeSpaceProbe() = fixture { data, staging, archive, spec ->
        val old = File(staging, "image-00000000-0000-4000-8000-000000000002").apply { mkdir() }
        File(old, "large-unfinished-image").writeText("old extraction")
        File(staging, old.name + ".owner").writeText("initial-staging-v1\t${old.name}\n")
        val tx = InitialInstallTransaction(data, staging)
        assertNull(tx.recoverOrCheckSpace(spec, {
            assertFalse("Budget must observe space after reclamation", old.exists())
            spec.bytes + spec.unpackedBytes + 512L * 1024 * 1024
        }, { 0L }, { fail("Nothing has been published") }))
        assertTrue(archive.isFile)
    }
    @Test fun publishedGuestRegistersWithOnlyHeadroomWithoutConsultingArchiveOrBudget() = fixture { data, staging, archive, spec ->
        val tx = InitialInstallTransaction(data, staging)
        val guest = tx.install(spec, archive, Long.MAX_VALUE, { _, d -> unpack(d) })
        archive.delete()
        var registered: File? = null
        val recovered = InitialInstallTransaction(data, staging).recoverOrCheckSpace(spec,
            { fail("Published recovery must precede the full-space gate"); 512L * 1024 * 1024 },
            { fail("Published recovery must not request an archive"); 0L }, { registered = it })
        assertEquals(guest, recovered)
        assertEquals(guest, registered)
    }
    @Test fun staleCleanupPreservesUnknownTreesAndDoesNotFollowGuestSymlinks() = fixture { data, staging, archive, spec ->
        val unknown = File(staging, "image-unrecognized").apply { mkdir() }
        File(unknown, "sentinel").writeText("keep unknown")
        val outside = File(data, "other-guest").apply { mkdir() }
        File(outside, "history").writeText("keep personal data")
        val owned = File(staging, "image-00000000-0000-4000-8000-000000000003").apply { mkdir() }
        Files.createSymbolicLink(File(owned, "linked-directory").toPath(), outside.toPath())
        File(staging, owned.name + ".owner").writeText("initial-staging-v1\t${owned.name}\n")
        InitialInstallTransaction(data, staging).discardInterruptedStages()
        assertFalse(owned.exists())
        assertEquals("keep personal data", File(outside, "history").readText())
        assertEquals("keep unknown", File(unknown, "sentinel").readText())
        assertTrue(archive.isFile)
    }
    @Test fun failedOfflineCopyIsRemovedAndRetryUsesOneVerifiedArchive() = fixture { data, _, archive, spec ->
        val imports = File(data, "imports")
        val cache = InitialImageImportCache(imports)
        assertThrows(IOException::class.java) {
            cache.importImage(spec, { object : InputStream() {
                var first = true
                override fun read(): Int { if (first) { first = false; return 1 }; throw IOException("source failed") }
            } })
        }
        assertTrue(imports.listFiles().orEmpty().isEmpty())
        val result = cache.importImage(spec, { archive.inputStream() })!!
        assertEquals("verified-image", result.readText())
        assertEquals(1, imports.listFiles()!!.size)
    }
    @Test fun repeatedFullLengthBadHashOfflineImportsDoNotAccumulate() = fixture { data, _, archive, spec ->
        val imports = File(data, "imports")
        val cache = InitialImageImportCache(imports)
        repeat(3) {
            assertThrows(IOException::class.java) {
                cache.importImage(spec, { ByteArrayInputStream(ByteArray(archive.length().toInt()) { 65 }) })
            }
            assertTrue("A rejected full-size copy must release its storage", imports.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun offlineCopyPauseAndCancelReleaseOnlyOwnedFiles() = fixture { data, _, archive, spec ->
        val imports = File(data, "imports")
        val cache = InitialImageImportCache(imports)
        var paused = false
        assertNull(cache.importImage(spec, { archive.inputStream() }, { paused }, { _, _ -> paused = true }))
        assertTrue(imports.listFiles().orEmpty().isEmpty())
        cache.importImage(spec, { archive.inputStream() })
        File(imports, "unknown.txt").writeText("keep")
        val outside = File(data, "user-archive").apply { writeText("private") }
        Files.createSymbolicLink(File(imports, "${"a".repeat(64)}.tar.zst").toPath(), outside.toPath())
        cache.cancel()
        assertEquals("keep", File(imports, "unknown.txt").readText())
        assertEquals("private", outside.readText())
        assertFalse(File(imports, "${spec.sha256}.tar.zst").exists())
    }
    @Test fun offlineArchiveSurvivesProcessRestartAndExtractionFailure() = fixture { data, staging, archive, spec ->
        val imports = File(data, "imports").apply { mkdir() }
        // A killed copy can leave its complete bytes before atomic verification/promotion.
        File(imports, "${spec.sha256}.part").writeBytes(archive.readBytes())
        val recovered = InitialImageImportCache(imports).recover(spec)!!
        assertEquals("verified-image", recovered.readText())
        assertEquals(1, imports.listFiles()!!.size)
        assertThrows(IOException::class.java) {
            InitialInstallTransaction(data, staging).install(spec, recovered, Long.MAX_VALUE, { _, _ -> throw IOException("tar failed") })
        }
        val continued = InitialImageImportCache(imports).importImage(spec, { fail("Continue must use the verified offline archive"); archive.inputStream() })!!
        assertEquals(recovered, continued)
        InitialImageImportCache(imports).cancel()
        assertTrue(imports.listFiles()!!.isEmpty())
    }
    @Test fun killedPartialOfflineCopyIsReclaimedBeforeRetryBudget() = fixture { data, _, archive, spec ->
        val imports = File(data, "imports").apply { mkdir() }
        File(imports, "${spec.sha256}.part").writeText("partial")
        val cache = InitialImageImportCache(imports)
        assertNull(cache.recover(spec))
        assertTrue(imports.listFiles()!!.isEmpty())
        assertEquals("verified-image", cache.importImage(spec, { archive.inputStream() })!!.readText())
    }
    @Test fun cancelReclaimsOnlyRecognizableLegacyOfflineFiles() = fixture { data, _, _, _ ->
        val old = File(data, "local-00000000-0000-4000-8000-000000000001.tar.zst").apply { writeText("old import") }
        val unknown = File(data, "local-user-backup.tar.zst").apply { writeText("keep unknown") }
        val outside = File(data, "personal").apply { writeText("keep personal") }
        Files.createSymbolicLink(File(data, "local-00000000-0000-4000-8000-000000000002.tar.zst").toPath(), outside.toPath())
        InitialImageImportCache.discardLegacyCopies(data)
        assertFalse(old.exists())
        assertEquals("keep unknown", unknown.readText())
        assertEquals("keep personal", outside.readText())
    }
    @Test fun completedMarkerCanBeRecoveredButUnknownGuestCannotBeAdopted() = fixture { data, staging, archive, spec ->
        val tx = InitialInstallTransaction(data, staging)
        assertNull(tx.publishedImage(spec))
        tx.install(spec, archive, Long.MAX_VALUE, { _, d -> unpack(d) })
        assertNotNull(tx.publishedImage(spec)); assertNull(tx.publishedImage(spec.copy(id = "another-image")))
    }
}
