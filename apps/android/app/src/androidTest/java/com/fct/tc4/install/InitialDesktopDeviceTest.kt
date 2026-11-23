// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.misc.SwayDesktopProbe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Explicit opt-in device acceptance. Never installs into or clears the real guest. */
@RunWith(AndroidJUnit4::class)
class InitialDesktopDeviceTest {
    private fun context(): Context {
        assumeTrue(InstrumentationRegistry.getArguments().getString("initialImageAcceptance") == "true")
        return InstrumentationRegistry.getInstrumentation().targetContext
    }
    private fun workspace(context: Context) = File(context.filesDir, "initial-desktop-device-acceptance")
    private fun image(context: Context) = InitialImageRepository.available(context).first {
        InitialImageCatalog.compatible(it, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
    }
    /** Run only with a foreground, already live desktop; reads sockets/proc without changing preferences. */
    @Test fun existingDesktopReadinessRecognized() = runBlocking<Unit> {
        val context = context()
        val probe = SwayDesktopProbe(File(context.filesDir, "tmp"))
        val desktop = withTimeoutOrNull(15_000) {
            var current = probe.sample(400)
            while (current == null) { delay(200); current = probe.sample(400) }
            current
        }
        assertNotNull("A live foreground desktop must be recognized through its owned current sockets and processes", desktop)
    }
    @Test fun pauseDownloadAndExitProcess() {
        val context = context()
        val root = workspace(context)
        if (root.exists()) {
            assertEquals("initial-image-device-test-v1", File(root, ".owned-test").readText())
            root.deleteRecursively()
        }
        assertTrue(root.mkdirs()); File(root, ".owned-test").writeText("initial-image-device-test-v1")
        val spec = image(context)
        val downloads = File(root, "downloads")
        var pause = false
        val engine = ResumableImageDownloader()
        val file = engine.download(spec, downloads, { pause }) { count, _ -> if (count >= minOf(16L * 1024 * 1024, spec.bytes / 4)) pause = true }
        assertNull(file)
        val bytes = engine.partialBytes(spec, downloads)
        assertTrue("Public source must preserve a resumable prefix", bytes > 0 && bytes < spec.bytes)
        File(root, "paused.json").writeText(JSONObject().put("image", InitialImageCatalog.encode(listOf(spec))).put("offset", bytes).toString())
        File(root, "pause-result.json").writeText(JSONObject().put("passed", true).put("preservedBytes", bytes).put("uid", android.os.Process.myUid()).toString())
    }
    @Test fun resumeInNewProcessAndInstallIsolatedGuest() {
        val context = context()
        val root = workspace(context)
        assertEquals("initial-image-device-test-v1", File(root, ".owned-test").readText())
        val saved = JSONObject(File(root, "paused.json").readText())
        val spec = InitialImageCatalog.parse(saved.getString("image")).single()
        val offset = saved.getLong("offset")
        val downloads = File(root, "downloads")
        val engine = ResumableImageDownloader()
        assertEquals(offset, engine.partialBytes(spec, downloads))
        var lowestProgress = Long.MAX_VALUE
        val archive = engine.download(spec, downloads, progress = { count, _ -> lowestProgress = minOf(lowestProgress, count) })!!
        assertTrue("Resume must not restart from zero", lowestProgress >= offset)
        val registeredBefore = Global.installedContainers.toSet()
        val data = File(root, "data").apply { mkdir() }
        val privateState = File(root, "installer-state").apply { mkdir() }
        val guest = InitialDesktopInstaller(context, data, privateState, registerGuest = false).install(spec, archive)
        assertEquals(registeredBefore, Global.installedContainers.toSet())
        assertTrue(File(guest, "etc/machine-id").readText().trim().matches(Regex("[a-f0-9]{32}")))
        val otherData = File(root, "existing-data").apply { mkdir() }
        val protected = File(otherData, "omarchy").apply { mkdir() }
        File(protected, "history").writeText("keep-device-fixture-history")
        try {
            InitialDesktopInstaller(context, otherData, privateState, registerGuest = false).install(spec, archive)
            fail("Existing guest must never be replaced")
        } catch (_: IOException) { }
        assertEquals("keep-device-fixture-history", File(protected, "history").readText())
        val tmp = File(root, "guest-tmp").apply { mkdir() }
        val prootTmp = File(root, "proot-tmp").apply { mkdir() }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val output = File(root, "headless.log")
        val command = listOf(File(native, "libproot-omarchy-readonly.so").absolutePath, "-0", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L",
            "--rootfs=${guest.canonicalPath}", "--cwd=/root", "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=${tmp.canonicalPath}:/tmp", "--bind=${tmp.canonicalPath}:/dev/shm", "/usr/bin/env", "-i", "HOME=/root", "USER=root",
            "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=zh_CN.UTF-8", "/bin/bash", "-lc", "omarchy-playwright128 check && omarchy-playwright128 probe chromium headless")
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output)
        builder.environment().apply {
            put("PROOT_LOADER", File(native, "libproot-omarchy-loader-aarch64.so").absolutePath)
            put("PROOT_LOADER_32", File(native, "libproot-omarchy-loader-arm.so").absolutePath)
            put("PROOT_TMP_DIR", prootTmp.canonicalPath)
            put("LD_LIBRARY_PATH", File(context.filesDir, "bootstrap/lib").absolutePath)
        }
        val process = builder.start()
        if (!process.waitFor(3, TimeUnit.MINUTES)) { process.destroyForcibly(); fail("Fresh-image probe timed out") }
        assertEquals(output.readText().takeLast(2000), 0, process.exitValue())
        assertTrue(output.readText().contains("\"status\":\"passed\""))
        File(root, "acceptance-result.json").writeText(JSONObject().put("passed", true).put("resumeOffset", offset)
            .put("lowestSubsequentProgress", lowestProgress).put("imageId", spec.id).put("imageSha256", spec.sha256)
            .put("freshIsolatedInstall", true).put("existingGuestPreserved", true).put("registrationUnchanged", true)
            .put("headlessProbe", "passed").put("uid", android.os.Process.myUid()).toString())
    }
}
