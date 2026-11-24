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
                str(MISC / 'DesktopReturnPolicy.kt'), str(harness),
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
        for block in [vm.split('fun exitContainer()', 1)[1].split('fun enterGui()', 1)[0],
                      vm.split('override fun onCleared()', 1)[1],
                      vm.split('Global.newSession(onFinished = { exitCode ->', 1)[1].split('Global.setupEnvironment()', 1)[0],
                      vm.split('if (!setupEnvironment())', 1)[1].split('startKeepAliveService()', 1)[0],
                      vm.split('if (!launchCompleted) {', 1)[1].split('_isInitialized.value = true', 1)[0]]:
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
        self.assertLess(callback.index('if (exited) return@newSession'), callback.index('Global.x11SessionActive = false'))


if __name__ == '__main__':
    unittest.main()
