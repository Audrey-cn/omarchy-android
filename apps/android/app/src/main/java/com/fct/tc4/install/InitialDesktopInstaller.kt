// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.Context
import android.os.Process
import android.system.Os
import com.fct.tc4.ui.misc.Global
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Android native tools/identity adapter. Alternate roots are for isolated instrumentation only. */
class InitialDesktopInstaller(
    private val context: Context,
    private val dataRoot: File = context.dataDir,
    private val stateRoot: File = File(context.filesDir, "initial-desktop"),
    private val registerGuest: Boolean = true
) {
    /** Use the same persisted route selection for every initial image download. */
    fun createDownloader(onSource: (Int, Int) -> Unit = { _, _ -> }): ResumableImageDownloader =
        ResumableImageDownloader(onSource = onSource, mirrorStore = DownloadMirrorStore(context))

    private fun transaction(): InitialInstallTransaction {
        if (registerGuest && dataRoot.canonicalFile != context.dataDir.canonicalFile) throw IOException("Invalid desktop root")
        if (Files.isSymbolicLink(stateRoot.toPath()) || (!stateRoot.isDirectory && !stateRoot.mkdirs())) throw IOException("Invalid installation state directory")
        return InitialInstallTransaction(dataRoot, File(stateRoot, "staging"))
    }
    /** Recovery registers a complete owned publication before consulting cache or free space. */
    fun recoverOrCheckSpace(spec: ImageSpec, downloadedBytes: () -> Long): File? =
        transaction().recoverOrCheckSpace(spec, { stateRoot.usableSpace }, downloadedBytes, ::complete)

    fun discardInterruptedStages() { transaction().discardInterruptedStages() }

    fun install(spec: ImageSpec, archive: File): File {
        val transaction = transaction()
        val guest = transaction.publishedImage(spec) ?: transaction.install(spec, archive, stateRoot.usableSpace,
            { input, stage -> extract(input, stage) }, { stage -> prepare(stage) })
        complete(guest)
        return guest
    }
    private fun complete(guest: File) {
        requireConfiguration(guest)
        if (registerGuest) {
            InitialDesktopRegistration.commit(Global.installedContainers, Global.autoLaunch) { installed, firstLaunchDone, autoLaunch ->
                Global.sharedPrefs.edit().putStringSet("installed_containers", installed)
                    .putBoolean("first_launch_done", firstLaunchDone).putString("auto_launch", autoLaunch).commit()
            }
        }
    }
    private fun extract(archive: File, stage: File) {
        val bootstrap = File(context.filesDir, "bootstrap")
        val tar = File(bootstrap, "bin/tar")
        val zstd = File(bootstrap, "bin/zstd")
        if (!tar.canExecute() || !zstd.canExecute()) throw IOException("Native installation tools are not ready")
        stateRoot.mkdirs()
        val log = File(stateRoot, "extract-${UUID.randomUUID()}.log")
        try {
            val builder = ProcessBuilder(tar.absolutePath, "--no-same-owner", "--preserve-permissions",
                "--delay-directory-restore", "-xf", archive.absolutePath, "-C", stage.absolutePath)
                .directory(context.dataDir).redirectErrorStream(true).redirectOutput(log)
            builder.environment()["PATH"] = File(bootstrap, "bin").absolutePath + ":/system/bin"
            builder.environment()["LD_LIBRARY_PATH"] = File(bootstrap, "lib").absolutePath
            val child = builder.start()
            if (!child.waitFor(20, TimeUnit.MINUTES)) { child.destroyForcibly(); throw IOException("Desktop extraction timed out") }
            if (child.exitValue() != 0) throw IOException("Desktop extraction failed (${child.exitValue()})")
        } finally { log.delete() }
    }
    private fun requireConfiguration(stage: File) {
        val file = inside(stage, ".tiny.yaml")
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = 1024 * 1024 }
        val config = Yaml(SafeConstructor(options)).load<Any>(file.readText()) as? Map<*, *>
            ?: throw IOException("Invalid desktop configuration")
        if (config["code"] != "omarchy") throw IOException("Wrong initial desktop type")
    }
    private fun prepare(stage: File) {
        requireConfiguration(stage)
        for (path in listOf("usr/local/bin/omarchy-chromium", "usr/local/bin/omarchy-playwright128",
            "opt/omarchy-node/node-v24.15.0-linux-arm64/bin/node",
            "opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/chrome")) {
            if (!inside(stage, path).canExecute()) throw IOException("Desktop runtime is incomplete")
        }
        val username = command("/system/bin/id", "-un").trim()
        val uid = command("/system/bin/id", "-u").trim().toInt()
        val gid = command("/system/bin/id", "-g").trim().toInt()
        val names = command("/system/bin/id", "-Gn").trim().split(Regex("\\s+"))
        val ids = command("/system/bin/id", "-G").trim().split(Regex("\\s+")).map { it.toInt() }
        if (uid != Process.myUid() || names.size != ids.size || (names + username).any { !it.matches(Regex("[A-Za-z0-9_-]+")) })
            throw IOException("Android identity could not be verified")
        val aid = "aid_$username"
        val additions = mapOf(
            "passwd" to "$aid:x:$uid:$gid:Omarchy:/:/sbin/nologin\n",
            "shadow" to "$aid:*:18446:0:99999:7:::\n",
            "group" to names.zip(ids).joinToString("") { (name, id) -> "aid_$name:x:$id:root,$aid\n" },
            "gshadow" to names.joinToString("") { name -> "aid_$name:*::root,$aid\n" })
        for ((name, extra) in additions) {
            val file = inside(stage, "etc/$name")
            val mode = Os.stat(file.absolutePath).st_mode and 511
            Os.chmod(file.absolutePath, mode or 384)
            val original = file.readLines().filterNot { it.startsWith("aid_") }.joinToString("\n", postfix = "\n")
            file.writeText(original + extra)
            Os.chmod(file.absolutePath, mode)
        }
        val machineId = inside(stage, "etc/machine-id")
        if (machineId.readText().isBlank()) {
            val mode = Os.stat(machineId.absolutePath).st_mode and 511
            Os.chmod(machineId.absolutePath, mode or 128)
            machineId.writeText(UUID.randomUUID().toString().replace("-", "") + "\n")
            Os.chmod(machineId.absolutePath, mode)
        }
    }
    private fun inside(stage: File, path: String): File {
        val file = File(stage, path)
        if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            !file.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw IOException("Invalid desktop file")
        return file
    }
    private fun command(vararg args: String): String {
        val child = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw IOException("Identity query timed out") }
        val bytes = child.inputStream.readBytes()
        if (child.exitValue() != 0 || bytes.size > 16 * 1024) throw IOException("Identity query failed")
        return bytes.toString(Charsets.UTF_8)
    }
}
