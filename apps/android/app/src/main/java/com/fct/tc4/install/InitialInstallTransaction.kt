// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/** Publishes a new guest only. Existing guests, including unregistered ones, are protected. */
class InitialInstallTransaction(private val dataDirectory: File, private val stagingParent: File) {
    companion object {
        const val HEADROOM_BYTES = 512L * 1024 * 1024
        private const val MARKER = ".omarchy-initial-image"
        fun additionalBytes(spec: ImageSpec, downloaded: Long): Long =
            Math.addExact(Math.addExact(spec.bytes - downloaded.coerceIn(0, spec.bytes), spec.unpackedBytes), HEADROOM_BYTES)
    }
    fun install(spec: ImageSpec, archive: File, availableBytes: Long,
                extract: (File, File) -> Unit, prepare: (File) -> Unit = {}): File {
        InitialImageCatalog.validate(spec)
        checkedDirectory(dataDirectory)
        val lock = File(dataDirectory, ".initial-desktop.lock")
        if (Files.isSymbolicLink(lock.toPath())) throw IOException("Invalid install lock")
        return FileOutputStream(lock, true).channel.use { channel -> channel.lock().use {
            val finalDirectory = File(dataDirectory, "omarchy")
            if (Files.exists(finalDirectory.toPath(), NOFOLLOW_LINKS)) throw IOException("An existing desktop cannot be replaced")
            if (availableBytes < spec.unpackedBytes + HEADROOM_BYTES) throw IOException("Insufficient installation space")
            if (!Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS) || archive.length() != spec.bytes || hash(archive) != spec.sha256)
                throw IOException("Initial image verification failed")
            checkedDirectory(stagingParent)
            val stage = Files.createTempDirectory(stagingParent.toPath(), "image-${spec.sha256.take(12)}-").toFile()
            try {
                extract(archive, stage)
                requireGuest(stage)
                prepare(stage)
                requireGuest(stage)
                File(stage, MARKER).apply { writeText(marker(spec)); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
                if (Files.exists(finalDirectory.toPath(), NOFOLLOW_LINKS)) throw IOException("Desktop appeared during initial installation")
                Files.move(stage.toPath(), finalDirectory.toPath(), ATOMIC_MOVE)
                finalDirectory
            } finally { if (stage.exists()) stage.deleteRecursively() }
        } }
    }
    /** Recover only a fully published image from our own interrupted install journal. */
    fun publishedImage(spec: ImageSpec): File? {
        val target = File(dataDirectory, "omarchy")
        if (Files.isSymbolicLink(target.toPath()) || !target.isDirectory) return null
        val receipt = File(target, MARKER)
        if (!Files.isRegularFile(receipt.toPath(), NOFOLLOW_LINKS) || receipt.length() > 512 || receipt.readText() != marker(spec)) return null
        return try { requireGuest(target); target } catch (_: IOException) { null }
    }
    private fun requireGuest(directory: File) {
        for (path in listOf(".tiny.yaml", "usr/bin/bash")) {
            val file = File(directory, path)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() == 0L) throw IOException("Incomplete desktop image")
        }
        if (File(directory, ".tiny.yaml").length() > 1024 * 1024) throw IOException("Invalid desktop configuration")
    }
    private fun marker(spec: ImageSpec) = "initial-image-v1\t${spec.id}\t${spec.sha256}\t${spec.sourceSha}\n"
    private fun checkedDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Invalid installation directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create installation directory")
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
