"""Android QA policies run on the JVM without requiring an Android SDK."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / 'apps/android/app/src/main/java/com/fct/tc4'
MISC = JAVA_ROOT / 'ui/misc'


def kotlin_tools():
    gradle = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    compilers = sorted(gradle.glob('wrapper/dists/*/*/*/lib/kotlin-compiler-embeddable-*.jar'))
    java = shutil.which('java')
    jdks = sorted(gradle.glob('jdks/**/Contents/Home/bin/java'))
    if jdks:
        java = str(jdks[-1])
    if not java or not compilers:
        raise unittest.SkipTest('A JDK and the Gradle-distributed Kotlin compiler are required')
    return java, compilers[-1].parent


class AndroidQaRegressionTests(unittest.TestCase):
    def test_clipboard_history_and_ime_helpers_on_jvm(self):
        java, libraries = kotlin_tools()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            harness = tmp_path / 'ClipboardImePolicies.kt'
            harness.write_text(r'''package com.fct.tc4.ui.misc
fun main() {
    val persisted = mutableListOf<List<String>>()
    val history = ClipboardHistoryStore(persist = { persisted.add(it) })
    history.record(""); history.record(" \n\t")
    check(history.entries().isEmpty() && persisted.isEmpty())
    repeat(35) { history.record("item-$it") }
    check(history.entries() == (34 downTo 5).map { "item-$it" })
    history.record("item-10")
    check(history.entries().first() == "item-10")
    check(history.entries().count { it == "item-10" } == 1)
    check(history.entries().drop(1) == (34 downTo 5).filter { it != 10 }.map { "item-$it" })
    history.record("  exact text\n\t")
    check(history.entries().first() == "  exact text\n\t")
    val restored = ClipboardHistoryStore(persisted.last())
    check(restored.entries() == history.entries())
    val snapshot = history.entries()
    history.clear()
    check(history.entries().isEmpty() && persisted.last().isEmpty())
    check(snapshot.size == 30)
    val loaded = ClipboardHistoryStore(listOf("", "a", "a", " ", "b") + (1..40).map { "n$it" })
    check(loaded.entries().size == 30 && loaded.entries().take(2) == listOf("a", "b"))

    val sentinel = ImeBridgeHelper.SENTINEL
    check(sentinel == "\u200B")
    check(ImeBridgeHelper.stripSentinel("${sentinel}hello${sentinel}世界$sentinel") == "hello世界")
    check(ImeBridgeHelper.splitCommit(sentinel).isEmpty())
    check(ImeBridgeHelper.splitCommit("\n${sentinel}first\n\nsecond\n") == listOf(
        ImeBridgeHelper.Event.Newline, ImeBridgeHelper.Event.Text("first"),
        ImeBridgeHelper.Event.Newline, ImeBridgeHelper.Event.Newline,
        ImeBridgeHelper.Event.Text("second"), ImeBridgeHelper.Event.Newline))
    check(ImeBridgeHelper.send(sentinel) == listOf(ImeBridgeHelper.Event.Return))
    check(ImeBridgeHelper.send("${sentinel}unsent") == listOf(
        ImeBridgeHelper.Event.Text("unsent"), ImeBridgeHelper.Event.Return))
    check(ImeBridgeHelper.send("a\nb") == listOf(ImeBridgeHelper.Event.Text("a"),
        ImeBridgeHelper.Event.Newline, ImeBridgeHelper.Event.Text("b"), ImeBridgeHelper.Event.Return))
    for (text in listOf(null, "", sentinel, sentinel + sentinel)) {
        check(ImeBridgeHelper.shouldForwardBackspace(text, composing = false))
        check(!ImeBridgeHelper.shouldForwardBackspace(text, composing = true))
    }
    check(!ImeBridgeHelper.shouldForwardBackspace("${sentinel}local", composing = false))
    println("Clipboard and IME policies passed")
}
''')
            classes = tmp_path / 'classes'
            classes.mkdir()
            compile_result = subprocess.run([
                java, '-cp', str(libraries / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', str(next(libraries.glob('kotlin-stdlib-*.jar'))),
                '-d', str(classes), str(MISC / 'ClipboardHistoryStore.kt'),
                str(MISC / 'ImeBridgeHelper.kt'), str(harness),
            ], text=True, capture_output=True, timeout=90)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([java, '-cp', os.pathsep.join([
                str(classes), str(libraries / '*')]), 'com.fct.tc4.ui.misc.ClipboardImePoliciesKt'],
                text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Clipboard and IME policies passed', result.stdout)

    def test_ime_bridge_keeps_sentinel_and_separates_editor_actions_from_newlines(self):
        overlay = (MISC / 'OmarchyX11Overlay.kt').read_text()
        bridge = overlay.split('private class ImeBridgeEditText(', 1)[1].split('private class Controller(', 1)[0]
        self.assertIn('ImeBridgeHelper.SENTINEL', bridge)
        self.assertNotIn('setText("")', bridge)
        self.assertIn('override fun performEditorAction(', bridge)
        for action in ['IME_ACTION_SEND', 'IME_ACTION_GO', 'IME_ACTION_DONE']:
            self.assertIn(action, bridge)
        self.assertIn('ImeBridgeHelper.splitCommit(', bridge)
        self.assertIn('ImeBridgeHelper.shouldForwardBackspace(', bridge)
        self.assertIn('override fun deleteSurroundingTextInCodePoints(', bridge)
        self.assertIn('textAttribute: TextAttribute?', bridge)
        self.assertIn('sendKeyWithMods("Return", shift = true)', overlay)

    def test_clipboard_controls_and_resume_sync_are_wired_to_android_bridge(self):
        overlay = (MISC / 'OmarchyX11Overlay.kt').read_text()
        resume = overlay.split('fun onResume()', 1)[1].split('fun onPause()', 1)[0]
        self.assertIn('syncAndroidClipboard()', resume)
        self.assertIn('ClipboardHistoryStore(', overlay)
        self.assertIn('clipboard_get', overlay)
        self.assertIn('clipboard_sync', overlay)
        self.assertIn('socket.soTimeout', overlay)
        self.assertIn('readLine()', overlay)
        self.assertIn('ClipData.newPlainText(', overlay)
        self.assertIn('Thread.sleep(180)', overlay)
        dock = overlay.split('private fun expandDock()', 1)[1].split('private fun positionExpandedDock()', 1)[0]
        keyboard = overlay.split('private fun showOsk()', 1)[1].split('private fun makeBarBtn(', 1)[0]
        self.assertIn('omarchy_overlay_paste', dock)
        self.assertIn('configurePasteButton(', dock)
        self.assertIn('omarchy_overlay_copy', keyboard)
        self.assertIn('omarchy_overlay_paste', keyboard)
        self.assertIn('configurePasteButton(', keyboard)

    def test_onboarding_and_desktop_return_policies_on_jvm(self):
        java, libraries = kotlin_tools()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            harness = tmp_path / 'QaPolicies.kt'
            harness.write_text('''package com.fct.tc4.ui.misc
fun main() {
    val fresh = OnboardingProgress(false, false)
    check(fresh.stepToResume(3, null, false) == 3) // recreated Step 4
    check(fresh.stepToResume(1, 3, false) == 3) // visible guide wins
    for (invalid in listOf(-1, 4, Int.MAX_VALUE)) {
        check(fresh.stepToResume(invalid, null, false) == null)
    }
    for (progress in listOf(OnboardingProgress(true, false), OnboardingProgress(false, true))) {
        check(progress.stepToResume(3, null, false) == null)
        check(progress.stepToResume(3, 3, false) == 3)
        check(progress.stepToResume(3, null, true) == 3) // explicit replay survives recreation
    }
    for (session in listOf(false, true)) for (management in listOf(false, true)) {
        for (pending in listOf(false, true)) {
            check(shouldReturnToX11(session, management, pending) == (session && !management && !pending))
        }
    }
    val cold = DesktopLoadingState(warmResume = false, startedAt = 1000)
    check(cold.observe(1000, displayReady = true, desktopReady = false) == DesktopLoadingState.Appearance.SPLASH)
    check(cold.observe(1100, displayReady = false, desktopReady = true) == DesktopLoadingState.Appearance.SPLASH)
    check(cold.observe(1200, displayReady = true, desktopReady = true) == DesktopLoadingState.Appearance.HIDDEN)
    val fastResume = DesktopLoadingState(warmResume = true, startedAt = 2000)
    check(fastResume.observe(2299, false, false) == DesktopLoadingState.Appearance.HIDDEN)
    check(fastResume.observe(2300, true, false) == DesktopLoadingState.Appearance.HIDDEN)
    val slowResume = DesktopLoadingState(warmResume = true, startedAt = 3000)
    check(slowResume.observe(3299, false, false) == DesktopLoadingState.Appearance.HIDDEN)
    check(slowResume.observe(3300, false, false) == DesktopLoadingState.Appearance.PILL)
    check(slowResume.observe(3301, true, false) == DesktopLoadingState.Appearance.HIDDEN)
    // A completed restoration cannot flash again later in the same resume.
    check(slowResume.observe(4000, false, false) == DesktopLoadingState.Appearance.HIDDEN)
    println("Android QA policies passed")
}
''')
            classes = tmp_path / 'classes'
            classes.mkdir()
            compile_result = subprocess.run([
                java, '-cp', str(libraries / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join([
                    str(next(libraries.glob('kotlin-stdlib-*.jar'))),
                    str(next(libraries.glob('kotlinx-coroutines-core-jvm-*.jar'))),
                ]), '-d', str(classes), str(MISC / 'OnboardingReadiness.kt'),
                str(MISC / 'DesktopReturnPolicy.kt'), str(MISC / 'DesktopLoadingState.kt'), str(harness),
            ], text=True, capture_output=True, timeout=90)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([java, '-cp', os.pathsep.join([
                str(classes), str(libraries / '*')]), 'com.fct.tc4.ui.misc.QaPoliciesKt'],
                text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Android QA policies passed', result.stdout)

    def test_notification_services_use_bespoke_monochrome_icon(self):
        for service in [JAVA_ROOT / 'install/DesktopInstallService.kt', MISC / 'OmarchyKeepAliveService.kt']:
            self.assertIn('.setSmallIcon(R.drawable.ic_launcher_monochrome)', service.read_text())
        self.assertTrue((ROOT / 'apps/android/app/src/main/res/drawable/ic_launcher_monochrome.xml').is_file())

    def test_onboarding_resume_storage_is_cleared_only_on_explicit_exit(self):
        overlay = (MISC / 'OmarchyX11Overlay.kt').read_text()
        self.assertIn('"onboarding_resume_step"', overlay)
        show = overlay.split('private fun showOnboardingStep(step: Int)', 1)[1]
        self.assertIn('putInt(ONBOARDING_RESUME_STEP, step)', show)
        close = overlay.split('private fun closeOnboarding(action: OnboardingExit)', 1)[1].split('private fun showOnboardingStep', 1)[0]
        self.assertIn('putInt(ONBOARDING_RESUME_STEP, -1)', close)
        self.assertEqual(overlay.count('putInt(ONBOARDING_RESUME_STEP, -1)'), 1)
        self.assertNotIn('onboardingReadiness ?: return', close)
        attach = overlay.split('fun attachIfNeeded()', 1)[1].split('fun detach()', 1)[0]
        self.assertIn('restoreOnboardingIfInProgress()', attach)
        resume = overlay.split('override fun onActivityResumed', 1)[1].split('override fun onActivityPaused', 1)[0]
        self.assertIn('restoreOnboardingIfInProgress()', resume)
        self.assertIn('Global.userRequestedManagementPage = false', resume)

    def test_onboarding_footer_has_stroke_and_elevation_clearance(self):
        overlay = (MISC / 'OmarchyX11Overlay.kt').read_text()
        guide = overlay.split('private fun showOnboardingStep(step: Int)', 1)[1]
        card = guide.split('val card = LinearLayout(activity).apply {', 1)[1].split('val header', 1)[0]
        nav = guide.split('val navRow = LinearLayout(activity).apply {', 1)[1].split('card.addView(navRow', 1)[0]
        for layout in [card, nav]:
            self.assertIn('clipToPadding = false', layout)
            self.assertIn('clipChildren = false', layout)
        self.assertIn('setPadding(dp(2), dp(2), dp(2), dp(2))', nav)
        self.assertIn('dp(if (compact) 40 else 44)', nav)
        self.assertIn('marginEnd = dp(8)', nav)

    def test_live_desktop_redirect_is_wired_to_both_entry_points(self):
        main = (JAVA_ROOT / 'ui/main/MainActivity.kt').read_text()
        resume = main.split('override fun onResume()', 1)[1].split('override fun onNewIntent', 1)[0]
        new_intent = main.split('override fun onNewIntent', 1)[1].split('private fun', 1)[0]
        self.assertIn('returnToLiveDesktop()', resume)
        self.assertIn('setIntent(intent)', new_intent)
        self.assertIn('returnToLiveDesktop(intent)', new_intent)
        self.assertIn('Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT', main)
        self.assertIn('MainViewModel.pendingCommandText != null', main)
        self.assertIn('pendingImportCount > 0', main)
        self.assertIn('Intent.ACTION_SEND', main)
        self.assertIn('Intent.ACTION_SEND_MULTIPLE', main)

    def test_x11_session_flag_tracks_launch_exit_and_failed_startup(self):
        fragment = (JAVA_ROOT / 'ui/page/ContainerMainFragment.kt').read_text()
        vm = (JAVA_ROOT / 'ui/page/ContainerMainViewModel.kt').read_text()
        self.assertIn('Global.x11SessionActive = true', fragment)
        cleanup = vm.split('private fun stopRuntime(', 1)[1].split('override fun onCleared()', 1)[0]
        self.assertIn('Global.x11SessionActive = false', cleanup)
        for block in [vm.split('fun exitContainer()', 1)[1].split('fun enterGui()', 1)[0],
                      vm.split('override fun onCleared()', 1)[1],
                      vm.split('Global.newSession(onFinished = { exitCode ->', 1)[1].split('Global.setupEnvironment()', 1)[0],
                      vm.split('if (!launchCompleted) {', 1)[1].split('_isInitialized.value = true', 1)[0]]:
            self.assertIn('stopRuntime(', block)
        for block in [vm.split('if (!setupEnvironment())', 1)[1].split('startKeepAliveService()', 1)[0]]:
            self.assertIn('Global.x11SessionActive = false', block)

    def test_initial_management_navigation_does_not_restart_a_live_container(self):
        main = (JAVA_ROOT / 'ui/main/MainActivity.kt').read_text()
        normal = main.split('private fun onNormalLaunch()', 1)[1].split('private fun onImportFile', 1)[0]
        active = normal.split('if (Global.x11SessionActive)', 1)[1].split('if (Global.installedContainers', 1)[0]
        self.assertIn('Global.userRequestedManagementPage', active)
        self.assertIn('viewModel.navigateTo(MainViewModel.Screen.ContainerManage)', active)
        self.assertIn('returnToLiveDesktop()', active)
        self.assertIn('return', active)
        self.assertNotIn('Screen.ContainerMain', active)

    def test_late_shell_exit_callback_cannot_clear_a_new_desktop_session(self):
        vm = (JAVA_ROOT / 'ui/page/ContainerMainViewModel.kt').read_text()
        callback = vm.split('Global.newSession(onFinished = { exitCode ->', 1)[1].split('Global.setupEnvironment()', 1)[0]
        self.assertLess(callback.index('if (exited) return@newSession'), callback.index('stopRuntime('))
        self.assertLess(callback.index('Global.terminalSession !== ownedSession'), callback.index('stopRuntime('))

    def test_osk_repeat_keys_and_ime_sentinel_guard(self):
        overlay = (JAVA_ROOT / 'ui/misc/OmarchyX11Overlay.kt').read_text()
        self.assertIn('val repeatableKeys = setOf("BackSpace", "Left", "Down", "Up", "Right")', overlay)
        self.assertIn('keyRepeatInitialDelayMs = 350L', overlay)
        self.assertIn('keyRepeatIntervalMs = 55L', overlay)
        self.assertIn('onViewDetachedFromWindow', overlay)
        # Dual-mode compact/expanded OSK (default expanded) + intuitive arrows + symbol layer + long-press close guard
        self.assertIn('prefs.getBoolean("osk_compact_mode", false)', overlay)
        self.assertIn('MODE:sym', overlay)
        self.assertIn('omarchy_overlay_close_hold_hint', overlay)
        self.assertLess(overlay.index('OskKey("↑", "Up"'), overlay.index('OskKey("↓", "Down"'))
        restore = overlay.split('private fun restoreSentinel()', 1)[1].split('override fun onCreateInputConnection', 1)[0]
        self.assertIn('cur[0] == ImeBridgeHelper.SENTINEL[0] && !composing()', restore)


if __name__ == '__main__':
    unittest.main()
