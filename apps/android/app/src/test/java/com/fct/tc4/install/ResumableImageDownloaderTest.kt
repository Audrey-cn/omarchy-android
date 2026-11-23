// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableImageDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val image = ByteArray(256 * 1024) { (it * 11 + it / 256).toByte() }
    private val tag = "\"image-1\""

    @Test
    fun freshDownloadVerifiesAndReusesCache() = serve({ send(image, tag) }) { spec, requests ->
        val directory = temporary.newFolder()
        val progress = mutableListOf<Pair<Long, Long>>()
        val file = downloader().download(spec, directory, progress = { done, total ->
            progress += done to total
        })!!
        assertArrayEquals(image, file.readBytes())
        assertEquals(spec.bytes to spec.bytes, progress.last())
        assertTrue(progress.zipWithNext().all { (first, second) -> first.first <= second.first })
        assertEquals(file, downloader().download(spec, directory))
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun pausedPartialResumesInNewInstanceWithRangeAndIfRange() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertEquals(offset, downloader().partialBytes(spec, directory))
        val file = downloader().download(spec, directory)!!
        assertArrayEquals(image, file.readBytes())
        assertEquals(listOf(Request(), Request("bytes=$offset-", tag)), requests)
    }

    @Test
    fun fullResponseToRangeRequestStartsAgainAtZero() = serve({
        send(image, if (requestHeaders.getFirst("Range") == null) tag else "\"replacement\"")
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        val offsets = mutableListOf<Long>()
        val file = downloader().download(spec, directory, progress = { done, _ -> offsets += done })!!
        assertArrayEquals(image, file.readBytes())
        assertEquals(0L, offsets.first())
        assertEquals(listOf(Request(), Request("bytes=$offset-", tag)), requests)
    }

    @Test
    fun changedEntityCannotBeAppendedEvenWhenBytesMatch() = serve({
        sendImageOrRemainder(if (requestHeaders.getFirst("Range") == null) tag else "\"replacement\"")
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertEquals(Request("bytes=$offset-", tag), requests.last())
        assertEquals(0L, downloader().partialBytes(spec, directory))
    }

    @Test
    fun changedCatalogMetadataDiscardsOldPartial() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        pause(spec, directory)
        val updatedSpec = spec.copy(sourceSha = "b".repeat(40))
        assertEquals(0L, downloader().partialBytes(updatedSpec, directory))
        assertArrayEquals(image, downloader().download(updatedSpec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request()), requests)
    }

    @Test
    fun malformedRangeCannotAppendToValidPartial() = serve({
        val offset = requestedOffset()
        if (offset == null) send(image, tag)
        else send(
            image.copyOfRange(offset, image.size),
            tag,
            status = 206,
            range = "bytes ${offset + 1}-${image.lastIndex}/${image.size}"
        )
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertEquals(offset, downloader().partialBytes(spec, directory))
        assertEquals(Request("bytes=$offset-", tag), requests.last())
    }

    @Test
    fun rangeWithWrongTotalCannotAppendToValidPartial() = serve({
        val offset = requestedOffset()
        if (offset == null) send(image, tag)
        else send(
            image.copyOfRange(offset, image.size),
            tag,
            status = 206,
            range = "bytes $offset-${image.lastIndex}/${image.size + 1}"
        )
    }) { spec, _ ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertEquals(offset, downloader().partialBytes(spec, directory))
    }

    @Test
    fun weakEntityCannotAuthorizeAppend() = serve({
        sendImageOrRemainder("W/$tag")
    }) { spec, requests ->
        val directory = temporary.newFolder()
        pause(spec, directory)
        assertEquals(0L, downloader().partialBytes(spec, directory))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request()), requests)
    }

    @Test
    fun truncatedTransferCanResumeItsValidatedPrefix() = serve({
        if (requestedOffset() == null) {
            send(image.copyOf(32 * 1024), tag, declaredLength = image.size.toLong())
        } else {
            sendImageOrRemainder(tag)
        }
    }) { spec, requests ->
        val directory = temporary.newFolder()
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertEquals(32 * 1024L, downloader().partialBytes(spec, directory))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request("bytes=32768-", tag)), requests)
    }

    @Test
    fun oversizedFixedLengthIsRejectedBeforeWriting() = serve({
        send(image + 42.toByte(), tag)
    }) { spec, _ ->
        val directory = temporary.newFolder()
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertEquals(0L, downloader().partialBytes(spec, directory))
    }

    @Test
    fun oversizedChunkedBodyCannotLeaveVerifiedCacheEntry() = serve({
        send(image + 42.toByte(), tag, declaredLength = 0)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        repeat(2) {
            assertThrows(IOException::class.java) { downloader().download(spec, directory) }
            assertEquals(0L, downloader().partialBytes(spec, directory))
        }
        assertEquals(listOf(Request(), Request()), requests)
    }

    @Test
    fun invalidChecksumCannotLeaveVerifiedCacheEntry() = serve({
        val corrupt = image.copyOf().also { it[0] = 42 }
        send(corrupt, tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        repeat(2) {
            assertThrows(IOException::class.java) { downloader().download(spec, directory) }
            assertEquals(0L, downloader().partialBytes(spec, directory))
        }
        assertEquals(listOf(Request(), Request()), requests)
    }

    @Test
    fun completedCacheIsRehashedBeforeReuse() = serve({
        send(image, tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val verified = downloader().download(spec, directory)!!
        verified.writeBytes(image.copyOf().also { it[0] = 42 })
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request()), requests)
    }

    @Test
    fun cancelRemovesOnlyTheSelectedImageCache() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        pause(spec, directory)
        val sibling = File(directory, "unrelated").apply { writeText("preserve") }
        val other = spec.copy(sha256 = "f".repeat(64))
        pause(other, directory)
        val otherBytes = downloader().partialBytes(other, directory)
        assertTrue(otherBytes > 0)
        downloader().cancel(spec, directory)
        assertEquals("preserve", sibling.readText())
        assertEquals(0L, downloader().partialBytes(spec, directory))
        assertEquals(otherBytes, downloader().partialBytes(other, directory))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request(), Request()), requests)
    }

    @Test
    fun symlinkInCacheDirectoryIsRejectedWithoutNetworkOrMutation() = serve({
        send(image, tag)
    }) { spec, requests ->
        val outside = temporary.newFolder()
        val link = File(temporary.newFolder(), "cache")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { downloader().download(spec, link) }
        assertThrows(IOException::class.java) { downloader().partialBytes(spec, link) }
        assertThrows(IOException::class.java) { downloader().cancel(spec, link) }
        assertTrue(outside.list()!!.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun cacheFilesCannotFollowSymlinksIncludingDanglingLinks() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        pause(spec, directory)
        val outside = temporary.newFile().apply { writeText("preserve") }
        val owned = directory.listFiles()!!.filter { it.isFile }
        assertTrue(owned.size >= 2)
        for ((index, path) in owned.withIndex()) {
            val backup = path.readBytes()
            path.delete()
            val destination = if (index % 2 == 0) outside else File(outside.parentFile, "missing")
            Files.createSymbolicLink(path.toPath(), destination.toPath())
            assertThrows(IOException::class.java) { downloader().download(spec, directory) }
            assertThrows(IOException::class.java) { downloader().partialBytes(spec, directory) }
            assertThrows(IOException::class.java) { downloader().cancel(spec, directory) }
            assertTrue(Files.isSymbolicLink(path.toPath()))
            assertEquals("preserve", outside.readText())
            path.delete()
            path.writeBytes(backup)
        }
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun publishedCacheCannotFollowSymlinkEvenWhenTargetHasMatchingBytes() = serve({
        send(image, tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val file = downloader().download(spec, directory)!!
        val outside = temporary.newFile().apply { writeBytes(image) }
        file.delete()
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertArrayEquals(image, outside.readBytes())
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun defaultTransportRejectsEvenLoopbackHttp() = serve({
        send(image, tag)
    }) { spec, requests ->
        assertThrows(IOException::class.java) {
            ResumableImageDownloader().download(spec, temporary.newFolder())
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun explicitHttpTestModeRejectsRedirectOutsideLoopback() = serve({
        responseHeaders.set("Location", "http://127.0.0.1.example.com:9/image")
        sendResponseHeaders(302, -1)
    }) { spec, requests ->
        assertThrows(IOException::class.java) { downloader().download(spec, temporary.newFolder()) }
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun relativeRedirectCanCarryRangeAndValidator() = serve({
        if (requestURI.path == "/image") {
            responseHeaders.set("Location", "/asset")
            sendResponseHeaders(302, -1)
        } else {
            sendImageOrRemainder(tag)
        }
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        val resumed = Request("bytes=$offset-", tag)
        assertEquals(listOf(Request(), Request(), Request(), resumed), requests)
    }

    @Test
    fun changedRedirectPathCannotUsePreviousResourcesValidator() =
        verifyChangedRedirect("/asset-a", "/asset-b")

    @Test
    fun changedRedirectQueryCannotUsePreviousResourcesValidator() =
        verifyChangedRedirect("/asset?version=1", "/asset?version=2")

    @Test
    fun githubReleaseAssetCanResumeWithRotatedSigningCredentials() {
        val resource = effectiveImageResourceKey(URI("$githubObject?sp=r&sig=one&jwt=old&se=1"))
        val rotated = effectiveImageResourceKey(URI("$githubObject?sp=r&sig=two&jwt=new&se=2"))
        assertEquals(resource, rotated)
    }

    @Test
    fun githubReleaseAssetIdentityKeepsObjectAndRepresentationSelectors() {
        val first = effectiveImageResourceKey(URI("$githubObject?sig=one&versionid=1"))
        val otherVersion = effectiveImageResourceKey(URI("$githubObject?sig=two&versionid=2"))
        val otherObject = effectiveImageResourceKey(URI("${githubObject.dropLast(1)}1?sig=one&versionid=1"))
        assertTrue(first != otherVersion)
        assertTrue(first != otherObject)
    }

    private val githubObject = "https://release-assets.githubusercontent.com/" +
        "github-production-release-asset/123/4a078d5d-3d22-41a4-a50c-73c3369ecbd0"

    private fun verifyChangedRedirect(original: String, replacement: String) {
        val changed = AtomicBoolean()
        serve({
            when {
                requestURI.path == "/image" -> {
                    responseHeaders.set("Location", if (changed.get()) replacement else original)
                    sendResponseHeaders(302, -1)
                }
                changed.get() && requestedOffset() != null -> {
                    val offset = requestedOffset()!!
                    val wrongRemainder = image.copyOfRange(offset, image.size)
                    wrongRemainder[0] = (wrongRemainder[0].toInt() xor 0xff).toByte()
                    send(wrongRemainder, tag, status = 206, range = "bytes $offset-${image.lastIndex}/${image.size}")
                }
                else -> sendImageOrRemainder(tag)
            }
        }) { spec, requests ->
            val directory = temporary.newFolder()
            pause(spec, directory)
            changed.set(true)
            assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
            assertEquals(List(4) { Request() }, requests)
        }
    }

    @Test
    fun redirectLoopsAreBounded() = serve({
        responseHeaders.set("Location", "/image")
        sendResponseHeaders(302, -1)
    }) { spec, requests ->
        assertThrows(IOException::class.java) { downloader().download(spec, temporary.newFolder()) }
        assertTrue(requests.size in 2..8)
    }

    @Test
    fun pauseBeforeRequestDoesNotFetchOrRemovePartial() = serve({
        send(image, tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        val offset = pause(spec, directory)
        assertNull(downloader().download(spec, directory, shouldPause = { true }))
        assertEquals(offset, downloader().partialBytes(spec, directory))
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun pauseDuringFinalVerificationKeepsTheCompleteResponse() = serve({
        send(image, "W/$tag")
    }) { spec, requests ->
        val directory = temporary.newFolder()
        var lastProgress = 0L
        assertNull(downloader().download(
            spec,
            directory,
            shouldPause = { lastProgress == spec.bytes },
            progress = { done, _ -> lastProgress = done }
        ))
        assertEquals(spec.bytes, downloader().partialBytes(spec, directory))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request()), requests)
    }

    @Test
    fun interruptionWhileRecordingFinalByteResumesWithOnlyTheLastByte() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        assertThrows(InterruptedTransfer::class.java) {
            downloader().download(spec, directory, progress = { done, total ->
                if (done == total) throw InterruptedTransfer()
            })
        }
        val orphan = File(directory, "${spec.sha256}.entity.tmp").apply { writeText("incomplete metadata") }
        val finalByte = spec.bytes - 1
        assertEquals(finalByte, downloader().partialBytes(spec, directory))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
        assertEquals(listOf(Request(), Request("bytes=$finalByte-", tag)), requests)
        assertTrue(!orphan.exists())
    }

    @Test
    fun temporaryEntitySymlinkBlocksResumeWithoutTouchingItsTarget() = serve({
        sendImageOrRemainder(tag)
    }) { spec, requests ->
        val directory = temporary.newFolder()
        pause(spec, directory)
        val sentinel = temporary.newFile().apply { writeText("preserve") }
        val pendingEntity = File(directory, "${spec.sha256}.entity.tmp")
        Files.createSymbolicLink(pendingEntity.toPath(), sentinel.toPath())
        assertThrows(IOException::class.java) { downloader().download(spec, directory) }
        assertTrue(Files.isSymbolicLink(pendingEntity.toPath()))
        assertEquals("preserve", sentinel.readText())
        assertEquals(listOf(Request()), requests)
    }

    private class InterruptedTransfer : RuntimeException()

    private fun downloader() = ResumableImageDownloader(1500, 1500, allowHttpForTests = true)

    private fun pause(spec: ImageSpec, directory: File): Long {
        var downloaded = 0L
        val result = downloader().download(
            spec,
            directory,
            shouldPause = { downloaded > 0L },
            progress = { done, _ -> downloaded = done }
        )
        assertNull(result)
        assertTrue(downloaded in 1 until spec.bytes)
        return downloaded
    }

    private fun HttpExchange.requestedOffset() =
        requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()

    private fun HttpExchange.sendImageOrRemainder(entityTag: String) {
        val offset = requestedOffset()
        if (offset == null) send(image, entityTag)
        else send(
            image.copyOfRange(offset, image.size),
            entityTag,
            status = 206,
            range = "bytes $offset-${image.lastIndex}/${image.size}"
        )
    }

    private fun HttpExchange.send(
        body: ByteArray,
        entityTag: String,
        status: Int = 200,
        range: String? = null,
        declaredLength: Long = body.size.toLong()
    ) {
        responseHeaders.set("ETag", entityTag)
        range?.let { responseHeaders.set("Content-Range", it) }
        sendResponseHeaders(status, declaredLength)
        responseBody.write(body)
    }

    private data class Request(val range: String? = null, val ifRange: String? = null)

    private fun serve(
        handle: HttpExchange.() -> Unit,
        test: (ImageSpec, List<Request>) -> Unit
    ) {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += Request(exchange.requestHeaders.getFirst("Range"), exchange.requestHeaders.getFirst("If-Range"))
            try {
                exchange.handle()
            } catch (_: IOException) {
                // Paused or rejected transfers may close the connection mid-response.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val spec = ImageSpec(
                id = "test-image",
                name = "Test image",
                abi = "arm64-v8a",
                minAppVersionCode = 64,
                bytes = image.size.toLong(),
                unpackedBytes = image.size * 2L,
                sha256 = MessageDigest.getInstance("SHA-256").digest(image)
                    .joinToString("") { "%02x".format(it) },
                sourceSha = "a".repeat(40),
                urls = listOf("http://127.0.0.1:${server.address.port}/image")
            )
            test(spec, requests)
        } finally {
            server.stop(0)
        }
    }
}
