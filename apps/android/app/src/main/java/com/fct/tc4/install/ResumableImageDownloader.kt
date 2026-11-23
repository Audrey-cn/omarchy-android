// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val githubReleaseObject = Regex(
    "/github-production-release-asset/[0-9]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)
private val rotatingGithubCredentials = setOf("jwt", "sig", "st", "se", "skoid", "sktid", "skt", "ske")

internal fun effectiveImageResourceKey(uri: URI): String {
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    val path = uri.rawPath.ifEmpty { "/" }
    val query = if (scheme == "https" && host == "release-assets.githubusercontent.com" &&
        port == 443 && githubReleaseObject.matches(path)
    ) {
        // GitHub signs each download of the same repository/object path with short-lived credentials.
        uri.rawQuery?.split('&')?.filterNot { it.substringBefore('=') in rotatingGithubCredentials }
            ?.joinToString("&")?.ifEmpty { null }
    } else {
        uri.rawQuery
    }
    val identity = "$scheme://$host:$port$path" + (query?.let { "?$it" } ?: "")
    // Persist a digest so signed redirect URLs are never written to the cache.
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).toHex()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

class ResumableImageDownloader(
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 15000,
    private val allowHttpForTests: Boolean = false
) {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Download timeouts must be positive" }
    }

    /** Returns the length available for a safe resume or a published archive's full length. */
    fun partialBytes(spec: ImageSpec, directory: File): Long {
        val cache = cache(spec, directory)
        if (regularFileSize(cache.archive) == spec.bytes) return spec.bytes
        return checkpoint(spec, cache, fingerprint(spec))?.offset ?: 0L
    }

    /** Call after pausing an active download. */
    fun cancel(spec: ImageSpec, directory: File) {
        val cache = cache(spec, directory)
        exclusively(cache) {
            clearPartial(cache)
            Files.deleteIfExists(cache.archive)
        }
    }

    fun download(
        spec: ImageSpec,
        directory: File,
        shouldPause: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File? {
        val cache = cache(spec, directory, create = true)
        return exclusively(cache) {
            if (shouldPause()) return@exclusively null
            if (regularFileSize(cache.archive) != null) {
                when (verified(cache.archive, spec, shouldPause)) {
                    null -> return@exclusively null
                    true -> {
                        progress(spec.bytes, spec.bytes)
                        return@exclusively cache.archive.toFile()
                    }
                    false -> Files.delete(cache.archive)
                }
            }
            val specFingerprint = fingerprint(spec)
            val resume = checkpoint(spec, cache, specFingerprint)
            if (resume?.entity?.complete == true) {
                progress(spec.bytes, spec.bytes)
                return@exclusively verifyAndPublish(cache, spec, shouldPause)
            }
            var failure: IOException? = null
            val sources = spec.urls.indices.sortedBy { it != resume?.entity?.sourceIndex }
            for (sourceIndex in sources) {
                if (shouldPause()) return@exclusively null
                try {
                    return@exclusively transfer(
                        spec, cache, specFingerprint, sourceIndex,
                        resume?.takeIf { it.entity.sourceIndex == sourceIndex }, shouldPause, progress
                    )
                } catch (error: IOException) {
                    if (shouldPause()) return@exclusively null
                    failure?.let(error::addSuppressed)
                    failure = error
                }
            }
            throw failure ?: IOException("No image download URL is available")
        }
    }

    private fun transfer(
        spec: ImageSpec,
        cache: Cache,
        specFingerprint: String,
        sourceIndex: Int,
        requestedResume: ResumePoint?,
        shouldPause: () -> Boolean,
        progress: (Long, Long) -> Unit
    ): File? {
        val (response, resource, resume) = openResponse(spec.urls[sourceIndex], requestedResume)
        try {
            val encoding = singleHeader(response, "Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                throw IOException("Unexpected image content encoding")
            }
            val range = singleHeader(response, "Content-Range")
            val tag = singleHeader(response, "ETag")?.takeIf(::strongTag)
            val offset = when (val status = response.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    if (range != null) throw IOException("Full image response has a Content-Range")
                    0L
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (resume == null || range != "bytes ${resume.offset}-${spec.bytes - 1}/${spec.bytes}") {
                        throw IOException("Image response has an invalid Content-Range")
                    }
                    if (tag != resume.entity.tag) {
                        clearPartial(cache)
                        throw IOException("Image entity changed during resume")
                    }
                    resume.offset
                }
                else -> throw IOException("Image server returned HTTP $status")
            }
            val contentLength = singleHeader(response, "Content-Length")
            if (contentLength != null && contentLength.toLongOrNull() != spec.bytes - offset) {
                throw IOException("Image response has an unexpected Content-Length")
            }
            if (shouldPause()) return null
            if (offset == 0L) {
                clearPartial(cache)
                if (tag != null) {
                    writeEntity(cache, Entity(specFingerprint, sourceIndex, resource, tag, complete = false))
                }
            }
            try {
                if (!copyResponse(response, cache.partial, offset, spec.bytes, shouldPause, progress)) return null
            } catch (oversized: OversizedBody) {
                clearPartial(cache)
                throw oversized
            }
            // A complete checkpoint can survive pausing or exiting during the checksum pass.
            writeEntity(cache, Entity(specFingerprint, sourceIndex, resource, tag, complete = true))
            return verifyAndPublish(cache, spec, shouldPause)
        } finally {
            response.disconnect()
        }
    }

    private fun verifyAndPublish(cache: Cache, spec: ImageSpec, shouldPause: () -> Boolean): File? {
        when (verified(cache.partial, spec, shouldPause)) {
            null -> return null
            false -> {
                clearPartial(cache)
                throw IOException("Downloaded image failed SHA-256 or length verification")
            }
            true -> {
                regularFileSize(cache.archive)
                Files.move(cache.partial, cache.archive, ATOMIC_MOVE)
                Files.deleteIfExists(cache.entity)
                return cache.archive.toFile()
            }
        }
    }

    private fun copyResponse(
        response: HttpURLConnection,
        partial: Path,
        offset: Long,
        bytes: Long,
        shouldPause: () -> Boolean,
        progress: (Long, Long) -> Unit
    ): Boolean = response.inputStream.use { input ->
        FileChannel.open(partial, WRITE, CREATE, NOFOLLOW_LINKS).use { output ->
            if (offset > 0L && offset == bytes - 1 && output.size() == bytes) {
                output.truncate(offset)
            }
            if (output.size() != offset) throw IOException("Image partial changed during download")
            output.position(offset)
            val buffer = ByteArray(BUFFER_BYTES)
            var done = offset
            try {
                progress(done, bytes)
                while (done < bytes) {
                    if (shouldPause()) return false
                    val count = input.read(buffer, 0, minOf((bytes - done), buffer.size.toLong()).toInt())
                    if (count < 0) throw IOException("Image ended before the expected length")
                    val chunk = ByteBuffer.wrap(buffer, 0, count)
                    while (chunk.hasRemaining()) output.write(chunk)
                    done += count
                    progress(done, bytes)
                }
                if (input.read() != -1) throw OversizedBody()
            } finally {
                output.force(true)
            }
        }
        true
    }

    private fun openResponse(initialUrl: String, requestedResume: ResumePoint?): Response {
        var uri = parseUri(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            val isTestHttp = allowHttpForTests && uri.scheme.equals("http", ignoreCase = true) &&
                uri.host?.lowercase() in LOOPBACK_HOSTS
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null ||
                (!uri.scheme.equals("https", ignoreCase = true) && !isTestHttp)) {
                throw IOException("Image URL and redirects must use HTTPS")
            }
            val url = uri.toURL()
            val resource = effectiveImageResourceKey(uri)
            val resume = requestedResume?.takeIf { it.entity.resource == resource }
            val connection = (if (isTestHttp) url.openConnection(Proxy.NO_PROXY) else url.openConnection())
                as HttpURLConnection
            var keepOpen = false
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (resume != null) {
                    connection.setRequestProperty("Range", "bytes=${resume.offset}-")
                    connection.setRequestProperty("If-Range", checkNotNull(resume.entity.tag))
                }
                if (connection.responseCode !in REDIRECTS) {
                    keepOpen = true
                    return Response(connection, resource, resume)
                }
                if (redirects == MAX_REDIRECTS) throw IOException("Too many image download redirects")
                val location = singleHeader(connection, "Location")
                    ?: throw IOException("Image download redirect is missing Location")
                uri = uri.resolve(parseUri(location))
            } finally {
                if (!keepOpen) connection.disconnect()
            }
        }
        throw IOException("Too many image download redirects")
    }

    private fun parseUri(text: String): URI = try {
        URI(text)
    } catch (error: java.net.URISyntaxException) {
        throw IOException("Invalid image URL", error)
    }

    private fun singleHeader(response: HttpURLConnection, name: String): String? {
        val values = response.headerFields.entries
            .filter { it.key.equals(name, ignoreCase = true) }
            .flatMap { it.value }
        if (values.size > 1) throw IOException("Multiple $name headers in image response")
        return values.singleOrNull()
    }

    private fun checkpoint(spec: ImageSpec, cache: Cache, specFingerprint: String): ResumePoint? {
        val length = regularFileSize(cache.partial) ?: return null
        if (length !in 1..spec.bytes) return null
        val entity = readEntity(cache.entity) ?: return null
        if (entity.fingerprint != specFingerprint || entity.sourceIndex !in spec.urls.indices) return null
        if (entity.complete) return if (length == spec.bytes) ResumePoint(length, entity) else null
        if (entity.tag == null) return null
        // Revalidate the response boundary if the process died after the last write.
        if (length == spec.bytes) return if (length > 1L) ResumePoint(length - 1, entity) else null
        return ResumePoint(length, entity)
    }

    private fun readEntity(path: Path): Entity? {
        val size = regularFileSize(path) ?: return null
        if (size !in 1..MAX_ENTITY_BYTES) return null
        return try {
            DataInputStream(Files.newInputStream(path, NOFOLLOW_LINKS)).use { input ->
                if (input.readInt() != ENTITY_VERSION) return null
                val fingerprint = input.readUTF()
                val sourceIndex = input.readInt()
                val resource = input.readUTF()
                val tag = input.readUTF().ifEmpty { null }
                val complete = input.readBoolean()
                if (!SHA256.matches(resource) || (tag != null && !strongTag(tag)) || input.read() != -1) return null
                Entity(fingerprint, sourceIndex, resource, tag, complete)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun writeEntity(cache: Cache, entity: Entity) {
        val temporary = cache.entityTemp
        // An interrupted write can leave this file behind while the previous checkpoint is still valid.
        if (regularFileSize(temporary) != null) Files.delete(temporary)
        val channel = FileChannel.open(temporary, WRITE, CREATE_NEW, NOFOLLOW_LINKS)
        try {
            channel.use { output ->
                val data = DataOutputStream(Channels.newOutputStream(output))
                data.writeInt(ENTITY_VERSION)
                data.writeUTF(entity.fingerprint)
                data.writeInt(entity.sourceIndex)
                data.writeUTF(entity.resource)
                data.writeUTF(entity.tag.orEmpty())
                data.writeBoolean(entity.complete)
                data.flush()
                output.force(true)
            }
            Files.move(temporary, cache.entity, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun clearPartial(cache: Cache) {
        // Remove bytes before dropping the entity that describes them.
        Files.deleteIfExists(cache.partial)
        Files.deleteIfExists(cache.entity)
        Files.deleteIfExists(cache.entityTemp)
    }

    private fun cache(spec: ImageSpec, directory: File, create: Boolean = false): Cache {
        if (!SHA256.matches(spec.sha256) || spec.bytes <= 0L) {
            throw IOException("Image has an invalid SHA-256 or length")
        }
        val root = directory.toPath().toAbsolutePath().normalize()
        if (create && !Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectories(root)
        if (Files.exists(root, NOFOLLOW_LINKS) && !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw IOException("Image cache must be a real directory")
        }
        val ownedRoot = if (Files.isDirectory(root, NOFOLLOW_LINKS)) root.toRealPath() else root
        val cache = Cache(
            ownedRoot.resolve("${spec.sha256}.part"),
            ownedRoot.resolve("${spec.sha256}.entity"),
            ownedRoot.resolve("${spec.sha256}.entity.tmp"),
            ownedRoot.resolve("${spec.sha256}.tar.zst")
        )
        regularFileSize(cache.partial)
        regularFileSize(cache.entity)
        regularFileSize(cache.entityTemp)
        regularFileSize(cache.archive)
        return cache
    }

    private fun regularFileSize(path: Path): Long? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw IOException("Image cache entry must be a regular file")
        attributes.size()
    } catch (_: NoSuchFileException) {
        null
    }

    private fun <T> exclusively(cache: Cache, operation: () -> T): T {
        if (!activePaths.add(cache.partial)) throw IOException("Image download is already active")
        try {
            return operation()
        } finally {
            activePaths.remove(cache.partial)
        }
    }

    private fun verified(path: Path, spec: ImageSpec, shouldPause: () -> Boolean): Boolean? {
        if (regularFileSize(path) != spec.bytes) return false
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = spec.bytes
            while (remaining > 0) {
                if (shouldPause()) return null
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count < 0) return false
                digest.update(buffer, 0, count)
                remaining -= count
            }
            input.read() == -1 && digest.digest().toHex() == spec.sha256
        }
    }

    private fun fingerprint(spec: ImageSpec): String {
        val fields = listOf(
            spec.id, spec.name, spec.abi, spec.minAppVersionCode.toString(),
            spec.bytes.toString(), spec.unpackedBytes.toString(), spec.sha256, spec.sourceSha
        ) + spec.urls
        val encoded = fields.joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun strongTag(tag: String): Boolean = tag.length <= 1024 && STRONG_TAG.matches(tag)

    private data class Cache(val partial: Path, val entity: Path, val entityTemp: Path, val archive: Path)
    private data class Entity(
        val fingerprint: String,
        val sourceIndex: Int,
        val resource: String,
        val tag: String?,
        val complete: Boolean
    )
    private data class ResumePoint(val offset: Long, val entity: Entity)
    private data class Response(val connection: HttpURLConnection, val resource: String, val resume: ResumePoint?)
    private class OversizedBody : IOException("Image exceeds its expected length")

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val ENTITY_VERSION = 2
        const val MAX_ENTITY_BYTES = 4 * 1024L
        const val MAX_REDIRECTS = 5
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "[::1]")
        val SHA256 = Regex("[0-9a-f]{64}")
        val STRONG_TAG = Regex("\"[!#-~]*\"")
        val activePaths = ConcurrentHashMap.newKeySet<Path>()
    }
}
