"""Exercise the private shortcut store on the JVM and check exported entry wiring."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from test_android_qa_regressions import kotlin_tools

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'apps/android/app/src/main/java/com/fct/tc4'


class AndroidShortcutTests(unittest.TestCase):
    def test_only_durable_private_ids_resolve_commands(self):
        store = JAVA / 'ui/misc/LauncherShortcutStore.kt'
        self.assertTrue(store.is_file(), 'Shortcuts need a private durable command store')
        java, libraries = kotlin_tools()
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            context = tmp / 'Context.kt'
            context.write_text('''package android.content
abstract class Context {
    open val applicationContext: Context get() = this
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE = 0 }
}
interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun commit(): Boolean
    }
}
''')
            harness = tmp / 'ShortcutHarness.kt'
            harness.write_text('''package com.fct.tc4.ui.misc
import android.content.Context
import android.content.SharedPreferences
class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, String?>()
    var writable = true
    override fun getString(key: String, default: String?) = values[key] ?: default
    override fun edit() = object : SharedPreferences.Editor {
        val pending = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        override fun commit(): Boolean {
            if (!writable) return false
            values.putAll(pending)
            return true
        }
    }
}
class TestContext(val preferences: MemoryPreferences) : Context() {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(mode == MODE_PRIVATE)
        check(name.isNotBlank())
        return preferences
    }
}
fun main() {
    val disk = MemoryPreferences()
    val first = TestContext(disk)
    val command = "printf '%s' 'https://example.com/?a=1&b=2'\\nexec bash"
    val id = checkNotNull(LauncherShortcutStore.save(first, "omarchy", command))
    check(id.length == 36 && id != command && !id.contains("omarchy"))
    val recreated = TestContext(disk)
    val resolved = checkNotNull(LauncherShortcutStore.resolve(recreated, id))
    check(resolved.code == "omarchy" && resolved.command == command)
    check(LauncherShortcutStore.resolve(recreated, null) == null)
    check(LauncherShortcutStore.resolve(recreated, command) == null)
    check(LauncherShortcutStore.resolve(recreated, "unknown") == null)
    disk.values.remove("$id.command")
    check(LauncherShortcutStore.resolve(recreated, id) == null) // partial records fail closed
    disk.values["$id.command"] = command
    val second = checkNotNull(LauncherShortcutStore.save(first, "other", "echo second"))
    check(second != id)
    check(LauncherShortcutStore.resolve(first, id)?.command == command)
    disk.writable = false
    check(LauncherShortcutStore.save(first, "omarchy", "must not publish") == null)
    check(disk.values.values.none { it == "must not publish" })
    println("Private shortcut checks passed")
}
''')
            classes = tmp / 'classes'
            classes.mkdir()
            result = subprocess.run([
                java, '-cp', str(libraries / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', str(next(libraries.glob('kotlin-stdlib-*.jar'))),
                '-d', str(classes), str(context), str(store), str(harness),
            ], capture_output=True, text=True, timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([
                java, '-cp', os.pathsep.join([str(classes), str(libraries / '*')]),
                'com.fct.tc4.ui.misc.ShortcutHarnessKt',
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Private shortcut checks passed', result.stdout)

    def test_exported_activity_cannot_execute_raw_command_extras(self):
        main = (JAVA / 'ui/main/MainActivity.kt').read_text()
        handler = main.split('private fun onShortcutLaunch(intent: Intent)', 1)[1].split('companion object', 1)[0]
        self.assertIn('LauncherShortcutStore.resolve', handler)
        self.assertNotIn('getStringExtra("shortcut_command")', main)
        self.assertNotIn('getStringExtra("shortcut_code")', handler)
        creator = (JAVA / 'ui/misc/LauncherShortcutDialogFragment.kt').read_text()
        self.assertIn('LauncherShortcutStore.save', creator)
        self.assertIn('MainActivity::class.java', creator)
        self.assertNotIn('putExtra("shortcut_command"', creator)
        setup = (JAVA / 'ui/page/DesktopSetupActivity.kt').read_text()
        self.assertNotIn('putExtra("shortcut_command"', setup)
        open_desktop = setup.split('private fun openDesktop()', 1)[1]
        self.assertIn('LauncherShortcutStore.save', open_desktop)
        self.assertIn('MainActivity.ACTION_SHORTCUT', open_desktop)
        self.assertIn('putExtra(LauncherShortcutStore.EXTRA_ID, id)', open_desktop)

    def test_dark_system_bar_icons_are_explicit(self):
        main = (JAVA / 'ui/main/MainActivity.kt').read_text()
        self.assertIn('statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)', main)
        self.assertIn('navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)', main)

    def test_license_fallback_has_localized_resources(self):
        activity = (JAVA / 'ui/page/LicenseActivity.kt').read_text()
        self.assertIn('getString(R.string.tc4_license_fallback)', activity)
        for locale in ('values', 'values-zh-rCN'):
            strings = ET.parse(ROOT / f'apps/android/app/src/main/res/{locale}/strings.xml')
            self.assertTrue(strings.find("./string[@name='tc4_license_fallback']").text)

    def test_initial_catalog_refresh_is_quiet_and_follows_local_render(self):
        activity = (JAVA / 'ui/page/DesktopSetupActivity.kt').read_text()
        self.assertIn('loadImages(false, refreshAfterLoad = true)', activity)
        loader = activity.split('private fun loadImages(', 1)[1].split('private fun selected()', 1)[0]
        self.assertLess(loader.index('render(InitialDesktopState.state.value)'), loader.index('loadImages(true, quiet = true)'))
        failure = loader.split('catch (_: Exception)', 1)[1].split('finally', 1)[0]
        self.assertIn('if (!quiet', failure)
        self.assertNotIn('render(', failure)
        self.assertIn('catch (cancelled: CancellationException)', loader)


if __name__ == '__main__':
    unittest.main()
