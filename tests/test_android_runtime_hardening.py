"""Runtime ownership contracts and JVM-executed audio supervision (no device)."""

from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

from test_android_qa_regressions import kotlin_tools

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/android/app/src/main/java/com/fct/tc4"
CPP = ROOT / "apps/android/app/src/main/cpp"


def function(source, signature):
    """Extract a Kotlin/C block without depending on the following function's name."""
    start = source.index("{", source.index(signature))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start + 1:end - 1]


class RuntimeOwnershipTests(unittest.TestCase):
    def test_keepalive_holds_and_releases_important_xserver_binding(self):
        source = (JAVA / "ui/misc/OmarchyKeepAliveService.kt").read_text()
        self.assertIn("CmdEntryPointService::class.java", source)
        self.assertIn("BIND_AUTO_CREATE or BIND_IMPORTANT", source)
        self.assertIn("bindService(", source)
        self.assertIn("unbindService(", function(source, "override fun onDestroy()"))

    def test_x11_recovery_checks_live_socket_and_serializes_startup(self):
        source = (JAVA / "ui/page/ContainerMainViewModel.kt").read_text()
        startup = function(source, "private suspend fun ensureXServer(")
        self.assertIn("xserverMutex.withLock", source)
        self.assertIn("isXSocketReady", startup)
        self.assertIn("withTimeoutOrNull", startup)
        self.assertIn("stopRuntime(", startup)
        self.assertNotIn("if (xserverStarted) return@", startup)
        gui = function(source, "fun enterGui()")
        self.assertLess(gui.index("initializationJob?.join()"), gui.index("ensureXServer(args, command)"))
        self.assertLess(gui.index("ensureXServer(args, command)"), gui.index("GuiNavigationEvent.OpenX11"))
        self.assertIn("LocalSocketAddress.Namespace.FILESYSTEM", source)
        self.assertLess(startup.index("startGuestDisplay(guestCommand, restarting)"), startup.rindex("!exited"))
        recovery = function(source, "private suspend fun startGuestDisplay(")
        self.assertIn('if (!restarting || code != "omarchy")', recovery)
        self.assertIn("omarchy-recover-x11", recovery)
        self.assertIn("else result=127", recovery)
        self.assertIn("status == 0 && !exited", recovery)

    def test_every_runtime_exit_uses_idempotent_bridge_cleanup(self):
        source = (JAVA / "ui/page/ContainerMainViewModel.kt").read_text()
        cleanup = function(source, "private fun stopRuntime(")
        self.assertIn("if (exited) return", cleanup)
        self.assertLess(cleanup.index("ownedSession == null"), cleanup.index("Global.x11SessionActive = false"))
        for receipt in ["exited = true", "Global.x11SessionActive = false", "killXServer()",
                        "stopKeepAliveService()", "TinyAudio.stop()", "TinyMicrophone.stop()",
                        "TinyIpp.stop()", "TinyStorage.stop()", "_isInitialized.value = true"]:
            self.assertIn(receipt, cleanup)
        for signature in ["fun exitContainer()", "override fun onCleared()"]:
            self.assertIn("stopRuntime(", function(source, signature))
        callback = function(source, "Global.newSession(onFinished =")
        self.assertIn("Dispatchers.Main.immediate", callback)
        self.assertLess(callback.index("if (exited)"), callback.index("stopRuntime("))

    def test_explicit_exit_after_failed_startup_resets_only_its_owned_shell_once(self):
        source = (JAVA / "ui/page/ContainerMainViewModel.kt").read_text()
        cleanup = function(source, "private fun stopRuntime(")
        self.assertIn("session?.finishIfRunning()", cleanup)
        self.assertLess(cleanup.index("Os.kill(-pid, OsConstants.SIGQUIT)"), cleanup.index("session?.finishIfRunning()"))
        self.assertIn("OsConstants.ESRCH", cleanup)
        exit = function(source, "fun exitContainer()")
        self.assertIn("if (explicitExitCompleted) return", exit)
        self.assertNotIn("if (!stopRuntime()) return", exit)
        self.assertLess(exit.index("Global.terminalSession !== session"), exit.index("Global.newSession()"))

    def test_stale_startup_cannot_send_commands_to_a_replacement_shell(self):
        source = (JAVA / "ui/page/ContainerMainViewModel.kt").read_text()
        send = function(source, "private suspend fun sendStartupCommand(")
        self.assertIn("ownedSession == null", send)
        self.assertLess(send.index("Global.terminalSession !== ownedSession"), send.index("Global.sendCommand(command)"))

    def test_install_timeout_saves_pause_and_stops_without_joining_transfer(self):
        source = (JAVA / "install/DesktopInstallService.kt").read_text()
        timeout = function(source, "override fun onTimeout(startId: Int, fgsType: Int)")
        for receipt in ["pause.set(true)", "foregroundTimedOut.set(true)", "InitialDesktopState.update(",
                        "SetupPhase.PAUSED", "stopForeground(", "stopSelf()"]:
            self.assertIn(receipt, timeout)
        for blocking in [".join(", "awaitTermination(", "worker.submit(", "worker.execute("]:
            self.assertNotIn(blocking, timeout)
        self.assertLess(timeout.index("stopSelf()"), timeout.index("synchronized(controlLock)"))
        self.assertIn('"InitialDesktop-timeout-checkpoint"', timeout)
        self.assertIn("foregroundTimedOut.get()", function(source, "private fun publish("))

    def test_rejected_replacement_service_stops_without_stopping_active_duplicate(self):
        source = (JAVA / "install/DesktopInstallService.kt").read_text()
        start = function(source, "override fun onStartCommand(")
        self.assertIn("val accepted = workQueue.start(", start)
        self.assertIn("if (!accepted && !foregroundShown) stopSelf(startId)", start)
        self.assertIn("foregroundShown = true", function(source, "private fun showForeground("))

    def test_native_workers_expose_liveness_and_guard_join_after_failed_start(self):
        for bridge, filename in [("TinyAudio", "tiny_audio_jni.c"), ("TinyMicrophone", "tiny_microphone_jni.c")]:
            with self.subTest(bridge=bridge):
                source = (CPP / filename).read_text()
                start = function(source, f"Java_com_fct_tc4_{bridge}_nativeStart(")
                self.assertLess(start.index("if (g.thread_started)"), start.index("memset(&g"))
                query = function(source, f"Java_com_fct_tc4_{bridge}_nativeIsRunning(")
                self.assertIn("atomic_load_explicit(&g.running", query)
                stop = function(source, f"Java_com_fct_tc4_{bridge}_nativeStop(")
                self.assertRegex(stop, r"if\s*\(g\.thread_started\)")


class AudioSupervisionTests(unittest.TestCase):
    def test_real_kotlin_supervisors_retry_disconnect_errors_and_stop_start_races(self):
        java, libraries = kotlin_tools()
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            sources = []
            for bridge in ["TinyAudio", "TinyMicrophone"]:
                source = (JAVA / f"{bridge}.kt").read_text()
                # Substitute only the JNI/Android boundaries; execute production worker code.
                for signature, replacement in [
                    ("private external fun nativeStart(socketPath: String): Boolean", "private fun nativeStart(socketPath: String): Boolean = Probe.nativeStart(socketPath)"),
                    ("private external fun nativeIsRunning(): Boolean", "private fun nativeIsRunning(): Boolean = Probe.nativeIsRunning()"),
                    ("private external fun nativeStop()", "private fun nativeStop() = Probe.nativeStop()"),
                ]:
                    self.assertIn(signature, source)
                    source = source.replace(signature, replacement)
                source = re.sub(r'System.loadLibrary\("[^"]+"\)', 'Probe.loadLibrary()', source)
                source = source.replace("Thread.sleep(", "Probe.sleep(")
                path = tmp / f"{bridge}.kt"
                path.write_text(source)
                sources.append(path)
            (tmp / "Global.kt").write_text('''package com.fct.tc4.ui.misc
object Global { val appContext = Context() }
class Context { val filesDir = java.io.File("/tmp") }
''')
            (tmp / "Log.kt").write_text('''package android.util
object Log {
    fun w(tag: String, message: String, error: Throwable? = null) = 0
    fun e(tag: String, message: String, error: Throwable? = null) = 0
}
''')
            (tmp / "RuntimeHarness.kt").write_text(AUDIO_HARNESS)
            classes = tmp / "classes"
            classes.mkdir()
            result = subprocess.run([
                java, "-cp", str(libraries / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-classpath", str(next(libraries.glob("kotlin-stdlib-*.jar"))),
                "-d", str(classes), *map(str, sources), str(tmp / "Global.kt"), str(tmp / "Log.kt"), str(tmp / "RuntimeHarness.kt"),
            ], text=True, capture_output=True, timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), str(libraries / "*")]),
                                  "com.fct.tc4.RuntimeHarnessKt"], text=True, capture_output=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn("Runtime audio supervision passed", run.stdout)


AUDIO_HARNESS = r'''package com.fct.tc4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Probe {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val active = AtomicInteger()
    val maximumActive = AtomicInteger()
    val delays = java.util.Collections.synchronizedList(mutableListOf<Long>())
    @Volatile var mode = "disconnect"
    @Volatile var stopBridge: () -> Unit = {}
    @Volatile var restartBridge: () -> Unit = {}
    @Volatile var done = CountDownLatch(1)
    @Volatile var restarting = false
    fun reset(next: String, stop: () -> Unit, restart: () -> Unit) {
        starts.set(0); stops.set(0); active.set(0); maximumActive.set(0); delays.clear()
        mode = next; stopBridge = stop; restartBridge = restart
        done = CountDownLatch(1); restarting = false
    }
    fun loadLibrary() {}
    fun nativeStart(path: String): Boolean {
        check(path.endsWith(".tiny.audio") || path.endsWith(".tiny.mic"))
        val count = starts.incrementAndGet()
        maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        check(active.get() == 1) { "overlapping native workers" }
        if (mode == "errors" && count == 1) throw IllegalStateException("JNI runtime error")
        if (mode == "errors" && count == 2) throw UnsatisfiedLinkError("JNI linkage error")
        if (count >= 8 || (mode == "race" && count == 2)) stopBridge()
        return mode != "errors"
    }
    fun nativeIsRunning(): Boolean = mode == "healthy" && starts.get() < 8
    fun nativeStop() {
        check(active.decrementAndGet() == 0)
        stops.incrementAndGet()
        if (mode == "race" && !restarting) {
            restarting = true
            stopBridge()
            restartBridge() // a request while the previous native worker is cleaning up
        } else if (starts.get() >= 8 || (mode == "race" && starts.get() == 2)) done.countDown()
    }
    fun sleep(millis: Long) {
        delays.add(millis)
        if (mode == "healthy") {
            check(starts.get() == 1) { "healthy native stream was restarted" }
            stopBridge()
        }
        Thread.yield()
    }
}
fun main() {
    for ((start, stop) in listOf<Pair<() -> Unit, () -> Unit>>(
        TinyAudio::start to TinyAudio::stop, TinyMicrophone::start to TinyMicrophone::stop)) {
        for (mode in listOf("disconnect", "errors", "race")) {
            Probe.reset(mode, stop, start)
            start(); start() // start is idempotent
            check(Probe.done.await(5, TimeUnit.SECONDS)) { "$mode worker did not finish" }
            Thread.sleep(10)
            check(Probe.starts.get() == if (mode == "race") 2 else 8)
            check(Probe.stops.get() == Probe.starts.get()) { "native attempt was not cleaned up" }
            check(Probe.maximumActive.get() == 1)
            if (mode != "race") {
                check(Probe.delays.any { it >= 1000L }) { "retry did not back off" }
                check(Probe.delays.all { it in 1L..5000L }) { "retry exceeded cap" }
            }
            stop(); stop()
        }
        Probe.reset("healthy", stop, start)
        start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Probe.stops.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        check(Probe.starts.get() == 1 && Probe.stops.get() == 1)
        Thread.sleep(10)
    }
    println("Runtime audio supervision passed")
}
'''


if __name__ == "__main__":
    unittest.main()
