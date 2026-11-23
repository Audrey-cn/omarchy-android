// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

data class ManagedCoreFile(val path: String, val sha256: String, val mode: Int)
data class ManagedCoreSyncResult(
    val updated: Int,
    val removed: Int,
    val conflicts: List<String>,
    val legacyBackup: File? = null
)

/** Pure file I/O: no Android, PRoot, shell commands or user-configuration writes. */
class ManagedCoreFiles(
    private val guestDirectory: File,
    private val stateDirectory: File,
    private val openAsset: (String) -> InputStream
) {
    companion object {
        fun validate(entry: ManagedCoreFile) {
            val parts = entry.path.split('/')
            require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid managed path: ${entry.path}" }
            require(entry.path.none { it == '\\' || it.code < 32 || it.code == 127 }) { "Invalid managed path" }
            require((parts.size == 4 && parts.take(3) == listOf("usr", "local", "bin")) ||
                (parts.size >= 3 && parts[0] == "opt" && parts[1] in setOf("omarchy-chromium128", "omarchy-node"))) {
                "Unapproved managed path: ${entry.path}"
            }
            require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid managed SHA-256" }
            require(entry.mode == 420 || entry.mode == 493) { "Managed mode must be 0644 or 0755" }
        }

        private fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

        /** Reject every existing symlink component, including a dangling final link. */
        private fun checkedFile(root: File, relative: String): File {
            var current = root
            val parts = relative.split('/')
            parts.forEachIndexed { index, part ->
                current = File(current, part)
                require(!Files.isSymbolicLink(current.toPath())) { "Managed path is a symlink: $relative" }
                if (exists(current) && index < parts.lastIndex) {
                    require(current.isDirectory) { "Managed parent is not a directory: $relative" }
                }
            }
            require(current.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Managed path escapes its root" }
            return current
        }
    }

    fun sync(sourceSha: String, entries: List<ManagedCoreFile>): ManagedCoreSyncResult {
        require(sourceSha.matches(Regex("[0-9a-f]{40}"))) { "Invalid managed source SHA" }
        require(entries.isNotEmpty()) { "Empty managed manifest" }
        entries.forEach(::validate)
        require(entries.map { it.path }.toSet().size == entries.size) { "Duplicate managed paths" }
        require(!Files.isSymbolicLink(guestDirectory.toPath()) && guestDirectory.isDirectory) { "Missing or linked guest directory" }
        require(!Files.isSymbolicLink(stateDirectory.toPath())) { "Managed state is a symlink" }
        stateDirectory.mkdirs()
        require(stateDirectory.isDirectory) { "Cannot create managed state directory" }
        val guest = guestDirectory.canonicalFile
        val state = stateDirectory.canonicalFile
        val lockFile = checkedFile(state, "sync.lock")
        FileOutputStream(lockFile, true).channel.use { channel ->
            channel.lock().use {
                return syncLocked(guest, state, sourceSha, entries.sortedBy { it.path })
            }
        }
    }

    private fun syncLocked(guest: File, state: File, sourceSha: String, entries: List<ManagedCoreFile>): ManagedCoreSyncResult {
        val receipt = checkedFile(state, "manifest.tsv")
        val previous = linkedMapOf<String, ManagedCoreFile>()
        val legacy = !exists(receipt)
        if (!legacy) {
            require(receipt.isFile) { "Managed receipt is not a file" }
            val lines = receipt.readLines()
            require(lines.firstOrNull()?.matches(Regex("managed-core-v1\\t[0-9a-f]{40}")) == true) { "Invalid managed receipt" }
            for (line in lines.drop(1)) {
                val columns = line.split('\t')
                require(columns.size == 3) { "Invalid managed receipt entry" }
                val entry = ManagedCoreFile(columns[0], columns[1], columns[2].toInt())
                validate(entry)
                require(previous.put(entry.path, entry) == null) { "Duplicate managed receipt entry" }
            }
        }
        val desired = entries.associateBy { it.path }
        val currentHashes = linkedMapOf<String, String?>()
        for (path in (previous.keys + desired.keys).sorted()) {
            val target = checkedFile(guest, path)
            require(!exists(target) || target.isFile) { "Managed target is not a file: $path" }
            currentHashes[path] = if (exists(target)) hash(target) else null
        }
        val conflicts = currentHashes.filter { (path, current) ->
            current != null && current != desired[path]?.sha256 &&
                current != previous[path]?.sha256 && !(legacy && path in desired)
        }.keys.toList()
        if (conflicts.isNotEmpty()) return ManagedCoreSyncResult(0, 0, conflicts)

        val changed = entries.filter { entry ->
            val target = checkedFile(guest, entry.path)
            currentHashes[entry.path] != entry.sha256 || (exists(target) &&
                Files.getPosixFilePermissions(target.toPath()) != PosixFilePermissions.fromString(
                    if (entry.mode == 493) "rwxr-xr-x" else "rw-r--r--"))
        }
        val retired = previous.keys.filter { it !in desired && currentHashes[it] != null }
        val staging = Files.createTempDirectory(state.toPath(), "staging-").toFile()
        var backup: File? = null
        try {
            // Verify every needed payload before replacing even the first guest file.
            for (entry in changed) {
                val staged = checkedFile(staging, entry.path)
                staged.parentFile!!.mkdirs()
                openAsset("managed-core/files/${entry.path}").use { input ->
                    FileOutputStream(staged).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
                }
                require(hash(staged) == entry.sha256) { "Managed asset SHA-256 mismatch: ${entry.path}" }
                Files.setPosixFilePermissions(staged.toPath(), PosixFilePermissions.fromString(
                    if (entry.mode == 493) "rwxr-xr-x" else "rw-r--r--"))
            }
            // Stage and check all legacy backups before any target is modified.
            if (legacy) {
                for (entry in changed.filter { currentHashes[it.path] != null }) {
                    val original = checkedFile(guest, entry.path)
                    val saved = checkedFile(state, "legacy-backup/${entry.path}")
                    saved.parentFile!!.mkdirs()
                    if (!exists(saved)) {
                        val temporary = Files.createTempFile(saved.parentFile!!.toPath(), ".backup-", ".tmp")
                        try {
                            FileOutputStream(temporary.toFile()).use { output ->
                                original.inputStream().use { it.copyTo(output, 64 * 1024) }; output.fd.sync()
                            }
                            require(hash(temporary.toFile()) == currentHashes[entry.path]) { "Legacy file changed during backup: ${entry.path}" }
                            Files.move(temporary, saved.toPath(), ATOMIC_MOVE)
                        } finally { Files.deleteIfExists(temporary) }
                    }
                    require(saved.isFile && hash(saved) == currentHashes[entry.path]) { "Existing legacy backup differs: ${entry.path}" }
                    backup = File(state, "legacy-backup")
                }
            }
            for (entry in changed) {
                val target = checkedFile(guest, entry.path)
                val current = if (exists(target)) hash(target) else null
                require(current == currentHashes[entry.path]) { "Managed target changed during synchronization: ${entry.path}" }
                target.parentFile!!.mkdirs()
                checkedFile(guest, entry.path)
                Files.move(File(staging, entry.path).toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            for (path in retired) {
                val target = checkedFile(guest, path)
                require(hash(target) == currentHashes[path]) { "Retired managed file changed: $path" }
                Files.delete(target.toPath())
            }
            val temporaryReceipt = Files.createTempFile(state.toPath(), ".manifest-", ".tmp")
            try {
                FileOutputStream(temporaryReceipt.toFile()).use { output ->
                    val text = "managed-core-v1\t$sourceSha\n" + entries.joinToString("") { "${it.path}\t${it.sha256}\t${it.mode}\n" }
                    output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync()
                }
                checkedFile(state, "manifest.tsv")
                Files.move(temporaryReceipt, receipt.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporaryReceipt) }
            return ManagedCoreSyncResult(changed.size, retired.size, emptyList(), backup)
        } finally {
            staging.deleteRecursively()
        }
    }
}
