// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The real transfer/checkpoint implementation with deterministic HTTP responses, without live services. */
class DownloadMirrorTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image = ByteArray(256 * 1024) { (it * 11 + it / 256).toByte() }
    private val official = "https://github.com/Audrey-cn/omarchy-android/releases/download/image-v1/ubuntu.tar.zst"
    private val spec = ImageSpec("test", "Test", "arm64-v8a", 64, image.size.toLong(), image.size * 2L,
        MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }, "a".repeat(40), listOf(official))

    @Test
    fun configuredMirrorsFallBackToOfficialAfterTransportAndChecksumFailures() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply { set(DownloadMirrorStore.Mode.ACCEL) }
        val requests = mutableListOf<String>()
        val downloader = ResumableImageDownloader(mirrorStore = store, connectionFactory = { uri ->
            requests += uri.toString()
            when (uri.host) {
                "gh-proxy.com" -> throw IOException("Mirror unavailable")
                "ghfast.top" -> response(uri, image.copyOf().also { it[0] = 42 })
                else -> response(uri, image)
            }
        })
        assertArrayEquals(image, downloader.download(spec, temporary.newFolder())!!.readBytes())
        assertEquals(listOf("https://gh-proxy.com/$official", "https://ghfast.top/$official", official), requests)
    }

    @Test
    fun mirrorLengthMismatchCannotPublishAndReachesOfficialFallback() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply {
            set(DownloadMirrorStore.Mode.CUSTOM, "https://mirror.example.org/")
        }
        val requests = mutableListOf<String>()
        val downloader = ResumableImageDownloader(mirrorStore = store, connectionFactory = { uri ->
            requests += uri.toString()
            response(uri, if (uri.host == "mirror.example.org") image.copyOf(image.size - 1) else image)
        })
        assertArrayEquals(image, downloader.download(spec, temporary.newFolder())!!.readBytes())
        assertEquals(listOf("https://mirror.example.org/$official", official), requests)
    }

    @Test
    fun pausedCorruptCompleteMirrorResponseDoesNotBlockOfficialFallback() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply {
            set(DownloadMirrorStore.Mode.CUSTOM, "https://mirror.example.org/")
        }
        val directory = temporary.newFolder()
        fun downloader() = ResumableImageDownloader(mirrorStore = store, connectionFactory = { uri ->
            response(uri, if (uri.host == "mirror.example.org") image.copyOf().also { it[0] = 42 } else image)
        })
        var count = 0L
        assertNull(downloader().download(spec, directory, shouldPause = { count == spec.bytes },
            progress = { done, _ -> count = done }))
        assertArrayEquals(image, downloader().download(spec, directory)!!.readBytes())
    }

    @Test
    fun allBadRoutesLeaveNoVerifiedArchive() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply { set(DownloadMirrorStore.Mode.ACCEL) }
        val downloader = ResumableImageDownloader(mirrorStore = store, connectionFactory = { uri ->
            response(uri, image.copyOf().also { it[0] = 42 })
        })
        val directory = temporary.newFolder()
        assertThrows(IOException::class.java) { downloader.download(spec, directory) }
        assertEquals(false, File(directory, "${spec.sha256}.tar.zst").exists())
    }

    @Test
    fun transientReadFailuresRetryTheSameSourceFromTheLatestCheckpoint() {
        val connections = mutableListOf<HttpURLConnection>()
        val selected = mutableListOf<Pair<Int, Int>>()
        val downloader = ResumableImageDownloader(onSource = { index, total -> selected += index to total },
            connectionFactory = { uri ->
                resumableResponse(uri, failAfterPrefix = connections.size < 2).also { connections += it }
            })
        assertArrayEquals(image, downloader.download(spec, temporary.newFolder())!!.readBytes())
        assertEquals(listOf(null, "bytes=32768-", "bytes=65536-"), connections.map { it.getRequestProperty("Range") })
        assertEquals(listOf(null, "\"image\"", "\"image\""), connections.map { it.getRequestProperty("If-Range") })
        assertTrue(connections.all { it.url.toString() == official })
        assertEquals(listOf(1 to 1), selected)
    }

    @Test
    fun exhaustedReadRetriesKeepTheLatestPrefixForTheNextDownload() {
        val connections = mutableListOf<HttpURLConnection>()
        var interrupt = true
        val downloader = ResumableImageDownloader(connectionFactory = { uri ->
            resumableResponse(uri, failAfterPrefix = interrupt).also { connections += it }
        })
        val directory = temporary.newFolder()
        assertThrows(IOException::class.java) { downloader.download(spec, directory) }
        assertEquals(96 * 1024L, downloader.partialBytes(spec, directory))
        assertEquals(listOf(null, "bytes=32768-", "bytes=65536-"), connections.map { it.getRequestProperty("Range") })
        interrupt = false
        assertArrayEquals(image, downloader.download(spec, directory)!!.readBytes())
        assertEquals(4, connections.size)
        assertEquals("bytes=98304-", connections.last().getRequestProperty("Range"))
    }

    @Test
    fun exhaustedReadRetriesFallBackToOfficialWithoutAppendingMirrorBytes() {
        val store = DownloadMirrorStore(inMemoryMirrorPreferences()).apply {
            set(DownloadMirrorStore.Mode.CUSTOM, "https://mirror.example.org/")
        }
        val connections = mutableListOf<HttpURLConnection>()
        val selected = mutableListOf<Pair<Int, Int>>()
        val downloader = ResumableImageDownloader(mirrorStore = store,
            onSource = { index, total -> selected += index to total }, connectionFactory = { uri ->
                resumableResponse(uri, failAfterPrefix = uri.host == "mirror.example.org").also { connections += it }
            })
        assertArrayEquals(image, downloader.download(spec, temporary.newFolder())!!.readBytes())
        assertEquals(listOf("https://mirror.example.org/$official", "https://mirror.example.org/$official",
            "https://mirror.example.org/$official", official), connections.map { it.url.toString() })
        assertEquals(listOf(null, "bytes=32768-", "bytes=65536-", null), connections.map { it.getRequestProperty("Range") })
        assertEquals(listOf(1 to 2, 2 to 2), selected)
    }

    @Test
    fun pauseAfterReadFailurePreventsRetryAndPreservesThePartial() {
        var paused = false
        var attempts = 0
        val downloader = ResumableImageDownloader(connectionFactory = { uri ->
            attempts++
            resumableResponse(uri, failAfterPrefix = true, onReadFailure = { paused = true })
        })
        val directory = temporary.newFolder()
        assertNull(downloader.download(spec, directory, shouldPause = { paused }))
        assertEquals(1, attempts)
        assertEquals(32 * 1024L, downloader.partialBytes(spec, directory))
    }

    @Test
    fun clientErrorsDoNotRetryTheSameSource() {
        for (status in listOf(404, 429)) {
            var attempts = 0
            val downloader = ResumableImageDownloader(connectionFactory = { uri ->
                attempts++
                object : HttpURLConnection(uri.toURL()) {
                    override fun getResponseCode() = status
                    override fun getHeaderFields() = emptyMap<String, List<String>>()
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                }
            })
            assertThrows(IOException::class.java) { downloader.download(spec, temporary.newFolder()) }
            assertEquals(1, attempts)
        }
    }

    private fun resumableResponse(
        uri: URI,
        failAfterPrefix: Boolean = false,
        onReadFailure: () -> Unit = {}
    ): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
        private fun offset() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        override fun getResponseCode() = if (offset() == 0) HTTP_OK else HTTP_PARTIAL
        override fun getHeaderFields(): Map<String, List<String>> = buildMap {
            put("Content-Length", listOf((image.size - offset()).toString()))
            put("ETag", listOf("\"image\""))
            if (offset() != 0) put("Content-Range", listOf("bytes ${offset()}-${image.lastIndex}/${image.size}"))
        }
        override fun getInputStream(): InputStream {
            val body = ByteArrayInputStream(image.copyOfRange(offset(), if (failAfterPrefix) offset() + 32 * 1024 else image.size))
            if (!failAfterPrefix) return body
            return object : InputStream() {
                override fun read(): Int = throw IOException("Connection reset").also { onReadFailure() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (body.available() == 0) return read()
                    return body.read(bytes, offset, length)
                }
            }
        }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    private fun response(uri: URI, body: ByteArray): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
        override fun getResponseCode() = HTTP_OK
        override fun getHeaderFields() = mapOf("Content-Length" to listOf(body.size.toString()), "ETag" to listOf("\"image\""))
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
}
