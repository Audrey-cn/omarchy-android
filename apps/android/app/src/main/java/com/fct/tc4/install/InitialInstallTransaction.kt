// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
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
    /** Runs before archive acquisition: reclaim only journaled stages, then recover or budget. */
    fun recoverOrCheckSpace(spec: ImageSpec, availableBytes: () -> Long, downloadedBytes: () -> Long,
                            onRecovered: (File) -> Unit): File? = locked {
        InitialImageCatalog.validate(spec)
        cleanInterruptedStages()
        val completed = publishedImage(spec)
        if (completed != null) {
            onRecovered(completed)
            completed
        } else {
            requireAbsentGuest()
            val required = additionalBytes(spec, downloadedBytes())
            if (availableBytes() < required) throw IOException("Insufficient download/install space")
            null
        }
    }
    fun discardInterruptedStages() = locked { cleanInterruptedStages() }

    fun install(spec: ImageSpec, archive: File, availableBytes: Long,
                extract: (File, File) -> Unit, prepare: (File) -> Unit = {}): File = locked {
        InitialImageCatalog.validate(spec)
        requireAbsentGuest()
        cleanInterruptedStages()
        if (availableBytes < Math.addExact(spec.unpackedBytes, HEADROOM_BYTES)) throw IOException("Insufficient installation space")
        if (!Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS) || archive.length() != spec.bytes || hash(archive) != spec.sha256)
            throw IOException("Initial image verification failed")
        checkedDirectory(stagingParent)
        val stage = File(stagingParent, "image-${UUID.randomUUID()}")
        val journal = File(stagingParent, stage.name + ".owner")
        // Journal first: death can leave a small journal, never a large unowned extraction.
        Files.createFile(journal.toPath())
        FileOutputStream(journal).use { it.write(stageOwner(stage).toByteArray()); it.fd.sync() }
        try {
            Files.createDirectory(stage.toPath())
            extract(archive, stage)
            requireGuest(stage)
            prepare(stage)
            requireGuest(stage)
            File(stage, MARKER).apply { writeText(marker(spec)); setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
            requireAbsentGuest()
            val finalDirectory = File(dataDirectory, "omarchy")
            Files.move(stage.toPath(), finalDirectory.toPath(), ATOMIC_MOVE)
            finalDirectory
        } finally {
            deleteStage(stage.toPath())
            Files.deleteIfExists(journal.toPath())
        }
    }
    private fun <T> locked(action: () -> T): T {
        checkedDirectory(dataDirectory)
        val lock = File(dataDirectory, ".initial-desktop.lock")
        if (Files.isSymbolicLink(lock.toPath())) throw IOException("Invalid install lock")
        return FileOutputStream(lock, true).channel.use { channel -> channel.lock().use { action() } }
    }
    private fun requireAbsentGuest() {
        if (Files.exists(File(dataDirectory, "omarchy").toPath(), NOFOLLOW_LINKS)) throw IOException("An existing desktop cannot be replaced")
    }
    private fun stageOwner(stage: File) = "initial-staging-v1\t${stage.name}\n"
    private fun cleanInterruptedStages() {
        if (!Files.exists(stagingParent.toPath(), NOFOLLOW_LINKS)) return
        checkedDirectory(stagingParent)
        val name = Regex("image-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        for (journal in stagingParent.listFiles().orEmpty()) {
            val stage = File(stagingParent, journal.name.removeSuffix(".owner"))
            if (!journal.name.endsWith(".owner") || !name.matches(stage.name) ||
                !Files.isRegularFile(journal.toPath(), NOFOLLOW_LINKS) || journal.length() > 128 ||
                journal.readText() != stageOwner(stage)) continue
            deleteStage(stage.toPath())
            Files.delete(journal.toPath())
        }
    }
    /** walkFileTree does not follow symlinks, including links inside a half-extracted guest. */
    private fun deleteStage(stage: Path) {
        if (!Files.exists(stage, NOFOLLOW_LINKS)) return
        Files.walkFileTree(stage, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory); return FileVisitResult.CONTINUE
            }
        })
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

/** Owned offline media cache; a selected spec determines its recoverable archive path. */
class InitialImageImportCache(private val directory: File) {
    companion object {
        /** Former versions wrote these exact UUID names directly into the installer state root. */
        fun discardLegacyCopies(root: File) {
            if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid installation state directory")
            val name = Regex("local-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tar\\.zst")
            for (file in root.listFiles().orEmpty()) {
                if (name.matches(file.name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) Files.delete(file.toPath())
            }
        }
    }
    private val ownedName = Regex("[0-9a-f]{64}\\.(part|tar\\.zst)")
    private fun checkedDirectory() {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs()))
            throw IOException("Invalid offline image cache")
    }
    private fun paths(spec: ImageSpec): Pair<File, File> {
        InitialImageCatalog.validate(spec)
        checkedDirectory()
        val partial = File(directory, "${spec.sha256}.part")
        val archive = File(directory, "${spec.sha256}.tar.zst")
        for (file in listOf(partial, archive)) {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                throw IOException("Invalid offline image cache entry")
        }
        return partial to archive
    }
    /** Reclaim incomplete/invalid and unselected copies before the install space check. */
    fun recover(spec: ImageSpec): File? {
        val (partial, archive) = paths(spec)
        for (file in directory.listFiles().orEmpty()) {
            if (file != partial && file != archive && ownedName.matches(file.name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                Files.delete(file.toPath())
        }
        if (archive.exists()) {
            if (verified(archive, spec)) { Files.deleteIfExists(partial.toPath()); return archive }
            Files.delete(archive.toPath())
        }
        if (partial.exists()) {
            if (verified(partial, spec)) {
                Files.move(partial.toPath(), archive.toPath(), ATOMIC_MOVE)
                return archive
            }
            Files.delete(partial.toPath())
        }
        return null
    }
    fun importImage(spec: ImageSpec, open: () -> java.io.InputStream,
                    shouldPause: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): File? {
        recover(spec)?.let { return if (shouldPause()) null else it }
        if (shouldPause()) return null
        val (partial, archive) = paths(spec)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            java.nio.channels.FileChannel.open(partial.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val output = java.nio.channels.Channels.newOutputStream(channel)
                open().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (shouldPause()) return null
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > spec.bytes - bytes) throw IOException("Wrong local image length")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytes += count
                        progress(bytes, spec.bytes)
                    }
                    if (shouldPause()) return null
                    if (bytes != spec.bytes || digest.digest().joinToString("") { "%02x".format(it) } != spec.sha256)
                        throw IOException("Local image failed SHA-256 or length verification")
                }
                channel.force(true)
            }
            Files.move(partial.toPath(), archive.toPath(), ATOMIC_MOVE)
            return archive
        } finally { Files.deleteIfExists(partial.toPath()) }
    }
    fun cancel() {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return
        checkedDirectory()
        for (file in directory.listFiles().orEmpty()) {
            if (ownedName.matches(file.name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) Files.delete(file.toPath())
        }
    }
    private fun verified(file: File, spec: ImageSpec): Boolean {
        if (file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }
}


enum class InitialDesktopAction { LAUNCH, RECOVER, INSTALL }

fun initialDesktopAction(completed: Boolean, registered: Boolean): InitialDesktopAction =
    when {
        registered -> InitialDesktopAction.LAUNCH
        completed -> InitialDesktopAction.RECOVER
        else -> InitialDesktopAction.INSTALL
    }

object InitialDesktopRegistration {
    fun commit(installed: Set<String>, autoLaunch: String, persist: (Set<String>, Boolean, String) -> Boolean) {
        if (!persist(installed + "omarchy", true, autoLaunch.ifEmpty { "omarchy" }))
            throw IOException("Desktop registration could not be saved")
    }
}


/** At most one install may wait behind idle cancellation; filesystem work always uses the worker. */
class InitialDesktopWorkQueue(private val executor: java.util.concurrent.Executor,
                              private val running: java.util.concurrent.atomic.AtomicBoolean,
                              private val lock: Any = Any(), private val onIdle: () -> Unit = {}) {
    private enum class Work { INSTALL, CANCEL }
    private var active: Work? = null
    private var pendingStart: (() -> Unit)? = null

    fun start(onAccepted: () -> Unit = {}, operation: () -> Unit): Boolean = synchronized(lock) {
        if (active == Work.CANCEL && pendingStart == null) {
            onAccepted()
            pendingStart = operation
            true
        } else if (active != null) false
        else submit(Work.INSTALL, onAccepted, operation)
    }
    fun cancelIdle(onAccepted: () -> Unit = {}, operation: () -> Unit): Boolean = synchronized(lock) {
        if (active != null) false else submit(Work.CANCEL, onAccepted, operation)
    }
    private fun submit(work: Work, onAccepted: () -> Unit, operation: () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        active = work
        try {
            onAccepted()
            executor.execute { drain(operation) }
        } catch (error: Throwable) {
            finish()
            throw error
        }
        return true
    }
    private fun drain(initial: () -> Unit) {
        var operation = initial
        while (true) {
            try { operation() }
            catch (error: Throwable) {
                synchronized(lock) { pendingStart = null; finish() }
                throw error
            }
            operation = synchronized(lock) {
                val next = pendingStart
                pendingStart = null
                if (next != null) { active = Work.INSTALL; next }
                else { finish(); null }
            } ?: return
        }
    }
    private fun finish() {
        active = null
        running.set(false)
        onIdle()
    }
}
