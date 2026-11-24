"""Exercise desktop stop ownership and process identity without an Android device."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest

from test_android_qa_regressions import MISC, kotlin_tools


class DesktopShutdownTests(unittest.TestCase):
    def test_flush_ownership_and_survivor_identity_on_jvm(self):
        java, libraries = kotlin_tools()
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            harness = target / 'Shutdown.kt'
            harness.write_text('''package com.fct.tc4.ui.misc
import kotlinx.coroutines.*

fun main() = runBlocking {
    val events = mutableListOf<String>()
    val flush = CompletableDeferred<Unit>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val coordinator = DesktopShutdownCoordinator(scope, { events += "service/activity" })
    val old = Any()
    check(coordinator.claim(old) { error("stale session stopped") })
    val active = Any()
    check(coordinator.claim(active) { events += "TERM"; flush.await(); events += "bridges" })
    coordinator.release(old)
    val stopped = coordinator.requestStop()!!
    check(coordinator.requestStop() == null)
    coordinator.release(active) // onCleared during the flush
    check(!coordinator.claim(Any()) { error("admitted while stopping") })
    check(events == listOf("TERM"))
    flush.complete(Unit)
    stopped.join()
    check(events == listOf("TERM", "bridges", "service/activity"))
    check(coordinator.claim(Any()) {})
    val failures = mutableListOf<String>()
    val failing = DesktopShutdownCoordinator(scope, { failures += "finish" }, { failures += "error" })
    failing.claim(Any()) { throw IllegalStateException("bridge failure") }
    failing.requestStop()!!.join()
    check(failures == listOf("error", "finish"))
    check(failing.claim(Any()) {})
    scope.cancel()

    val original = listOf(
        DesktopProcessSnapshot(10, 1, 10, 10, 100),
        DesktopProcessSnapshot(11, 10, 11, 10, 110),
        DesktopProcessSnapshot(12, 11, 12, 12, 120),
        DesktopProcessSnapshot(13, 1, 13, 13, 130, tracerPid = 10),
        DesktopProcessSnapshot(14, 1, 14, 10, 140),
        DesktopProcessSnapshot(99, 1, 99, 99, 990)
    )
    val owned = desktopProcesses(10, original)
    check(owned.map { it.pid }.toSet() == setOf(10, 11, 12, 13, 14))
    check(desktopProcesses(0, original).isEmpty())
    check(desktopProcesses(-1, original).isEmpty())
    val survivors = survivingDesktopProcesses(owned, listOf(
        DesktopProcessSnapshot(11, 1, 11, 11, 110), // detached original child
        DesktopProcessSnapshot(12, 1, 12, 12, 999) // reused PID, another desktop
    ))
    check(survivors.map { it.pid } == listOf(11))
    check(parseDesktopProcessStat("10 (guest (shell)) S 2 10 10 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 12345 0") ==
        DesktopProcessSnapshot(10, 2, 10, 10, 12345))
    check(parseDesktopProcessStat("malformed") == null)
    println("Desktop shutdown policies passed")
}
''')
            classpath = os.pathsep.join([
                str(next(libraries.glob('kotlin-stdlib-*.jar'))),
                str(next(libraries.glob('kotlinx-coroutines-core-jvm-*.jar'))),
            ])
            classes = target / 'classes'
            compile_result = subprocess.run([
                java, '-cp', str(libraries / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(classes),
                str(MISC / 'DesktopShutdownCoordinator.kt'), str(MISC / 'DesktopProcessSnapshot.kt'),
                str(harness),
            ], text=True, capture_output=True, timeout=90)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([
                java, '-cp', os.pathsep.join([str(classes), str(libraries / '*')]),
                'com.fct.tc4.ui.misc.ShutdownKt',
            ], text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Desktop shutdown policies passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
