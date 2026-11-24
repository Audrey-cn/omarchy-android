// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private fun response(uri: URI, body: ByteArray): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
        override fun getResponseCode() = HTTP_OK
        override fun getHeaderFields() = mapOf("Content-Length" to listOf(body.size.toString()), "ETag" to listOf("\"image\""))
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
}
