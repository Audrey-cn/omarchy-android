"""Check browser identity, launch contracts and failure reporting without device access."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
BIN = REPO / "core/bin"
NODE = shutil.which("node")


@unittest.skipUnless(NODE, "Node is needed to exercise the JS CLI contract")
class BrowserToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="omarchy browser ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("omarchy-playwright", "omarchy-playwright-tool.cjs", "omarchy-playwright-chromium", "omarchy-playwright-firefox"):
            shutil.copy2(BIN / name, self.bin / name)
        helper = self.bin / "omarchy-node-runtime"
        helper.write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_NODE"\n')
        helper.chmod(0o755)
        flock = self.bin / "flock"
        flock.write_text('#!/bin/sh\nexit 0\n')
        flock.chmod(0o755)
        self.pw_home = self.root / "isolated playwright"
        package = self.pw_home / "node_modules/playwright"
        package.mkdir(parents=True)
        (package / "package.json").write_text('{"version":"1.65.1","main":"index.js"}')
        (package / "index.js").write_text('''
const fs = require('fs');
const type = {executablePath: () => process.env.TEST_BROWSER,
  launch: async () => {
    if (process.env.TEST_PROBE_NAV_ERROR) return {
      version: () => '159.0', close: async () => {},
      newContext: async () => ({
        setDefaultTimeout: () => {},
        tracing: {start: async () => {}, stop: async () => {}},
        newPage: async () => ({goto: async () => {throw new Error('net::ERR_CONNECTION_REFUSED');}}),
      }),
    };
    throw new Error('fixture browser startup failed');
  },
  launchPersistentContext: async (profile, options) => {
    let userClosed = false;
    return {
      pages: () => [{goto: async url => {
        fs.writeFileSync(process.env.TEST_ARGUMENTS, JSON.stringify({profile, options, url}));
        if (process.env.TEST_DESKTOP_NAV_ERROR) throw new Error('net::ERR_CONNECTION_REFUSED');
      }}],
      once: (event, resolve) => setTimeout(() => {userClosed = true; resolve();},
        process.env.TEST_DESKTOP_NAV_ERROR ? 80 : 0),
      close: async () => {
        const launch = JSON.parse(fs.readFileSync(process.env.TEST_ARGUMENTS, 'utf8'));
        fs.writeFileSync(process.env.TEST_ARGUMENTS, JSON.stringify({...launch, closedBeforeUser: !userClosed}));
      },
    };
  },
};
module.exports = {chromium: type, firefox: type, webkit: type};
''')
        (package / "cli.js").write_text('''
require('fs').writeFileSync(process.env.TEST_ARGUMENTS, JSON.stringify({
  args:process.argv.slice(2), cache:process.env.PLAYWRIGHT_BROWSERS_PATH}));
''')
        self.browser = self.root / "real browser fixture"
        self.browser.write_text('#!' + sys.executable + '\nimport json,os,sys\nopen(os.environ["TEST_ARGUMENTS"],"w").write(json.dumps(sys.argv[1:]))\n')
        self.browser.chmod(0o755)
        self.args_file = self.root / "arguments.json"
        self.results = self.root / "results"
        self.env = dict(os.environ, PATH=str(self.bin) + ":" + os.environ["PATH"],
                        TEST_NODE=NODE, TEST_BROWSER=str(self.browser),
                        TEST_ARGUMENTS=str(self.args_file), HOME=str(self.root),
                        OMARCHY_PLAYWRIGHT_HOME=str(self.pw_home),
                        OMARCHY_PLAYWRIGHT_RESULTS=str(self.results))

    def invoke(self, *args):
        return subprocess.run(["bash", str(self.bin / "omarchy-playwright"), *args],
                              env=self.env, cwd=self.root, capture_output=True, text=True)

    def test_browser_path_is_an_executable_not_gtk_wrapper(self):
        result = self.invoke("browser-path", "chromium")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.browser) + "\n")
        self.browser.unlink()
        missing = self.invoke("browser-path", "chromium")
        self.assertNotEqual(missing.returncode, 0)
        self.assertEqual(missing.stdout, "")

    def test_headless_install_selects_only_matching_shell_and_own_cache(self):
        result = self.invoke("install", "headless")
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(self.args_file.read_text())
        self.assertEqual(payload["args"], ["install", "--with-deps", "--only-shell", "chromium"])
        self.assertEqual(payload["cache"], str(self.pw_home / "browsers"))

    def test_external_cjs_uses_isolated_package(self):
        result = self.invoke("run", "-e", "console.log(require('playwright/package.json').version)")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1.65.1\n")

    def test_interrupted_package_cannot_be_treated_as_ready(self):
        (self.pw_home / "node_modules/playwright/cli.js").unlink()
        result = self.invoke("browser-path", "chromium")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_failed_browser_probe_is_not_reported_as_success(self):
        result = self.invoke("probe", "chromium")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        reports = list(self.results.glob("*/result.json"))
        self.assertEqual(len(reports), 1, result.stderr)
        report = json.loads(reports[0].read_text())
        self.assertEqual(report["status"], "failed")
        self.assertIn("fixture browser startup failed", report["error"])

    def test_firefox_desktop_uses_playwright_with_own_profile_and_wayland(self):
        url = "https://example.test/?q=中文&second=2"
        env = dict(self.env, WAYLAND_DISPLAY="wayland-1", XDG_RUNTIME_DIR="/tmp")
        result = subprocess.run(["bash", str(self.bin / "omarchy-playwright-firefox"), url],
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        launch = json.loads(self.args_file.read_text())
        self.assertEqual(launch["url"], url)
        self.assertEqual(launch["profile"], str(self.root / ".local/share/omarchy-playwright/firefox-desktop"))
        self.assertFalse(launch["options"]["headless"])
        self.assertEqual(launch["options"]["env"]["MOZ_ENABLE_WAYLAND"], "1")
        self.assertEqual(launch["options"]["env"]["GDK_BACKEND"], "wayland")

    def test_failed_initial_navigation_keeps_desktop_open_until_user_closes(self):
        env = dict(self.env, WAYLAND_DISPLAY="wayland-1", XDG_RUNTIME_DIR="/tmp",
                   TEST_DESKTOP_NAV_ERROR="1")
        result = subprocess.run(["bash", str(self.bin / "omarchy-playwright-firefox"), "http://127.0.0.1:1"],
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ERR_CONNECTION_REFUSED", result.stderr)
        self.assertFalse(json.loads(self.args_file.read_text())["closedBeforeUser"])

    def test_probe_navigation_failure_remains_a_failed_probe(self):
        self.env["TEST_PROBE_NAV_ERROR"] = "1"
        result = self.invoke("probe", "firefox")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        report = json.loads(next(self.results.glob("*/result.json")).read_text())
        self.assertEqual(report["status"], "failed")
        self.assertIn("ERR_CONNECTION_REFUSED", report["error"])


class BrowserInstallTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="omarchy browser install ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        shutil.copy2(BIN / "omarchy-install-playwright-browser", self.bin)
        stub = self.bin / "omarchy-playwright"
        stub.write_text('#!' + sys.executable + '''
import json, os, sys
with open(os.environ['TEST_CALLS'], 'a') as calls:
    calls.write(json.dumps(sys.argv[1:]) + '\\n')
if sys.argv[1] == 'probe' and os.environ.get('TEST_FAIL_MODE') == sys.argv[3]:
    sys.exit(27)
if 'chromium' in sys.argv[1:]:
    sys.exit(98)  # Chromium must use the independently pinned 128 entry.
''')
        stub.chmod(0o755)
        pinned = self.bin / "omarchy-playwright128"
        pinned.write_text(stub.read_text().replace("if 'chromium' in sys.argv[1:]:", "if 'install' in sys.argv[1:]:"))
        pinned.chmod(0o755)
        dpkg = self.bin / "dpkg-query"
        dpkg.write_text('#!' + sys.executable + '''
import os,sys
from pathlib import Path
missing=os.environ.get('TEST_MISSING_DEPENDENCY','')
if not Path(os.environ['TEST_DEPENDENCY_MARKER']).exists() and missing in ('all',sys.argv[-1]):
    print('unpacked')
else:
    print('installed')
''')
        dpkg.chmod(0o755)
        apt = self.bin / "apt-get"
        apt.write_text('#!' + sys.executable + '''
import json,os,sys
from pathlib import Path
with open(os.environ['TEST_APT_CALLS'],'a') as calls:
    calls.write(json.dumps(sys.argv[1:])+'\\n')
if os.environ.get('TEST_APT_FAILURE')==sys.argv[1]:
    sys.exit(42)
if sys.argv[1]=='install':
    Path(os.environ['TEST_DEPENDENCY_MARKER']).touch()
''')
        apt.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.apt_calls = self.root / "apt-calls.jsonl"
        self.apps = self.root / ".local/share/applications"
        self.env = dict(os.environ, HOME=str(self.root), TEST_CALLS=str(self.calls),
                        PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        TEST_APT_CALLS=str(self.apt_calls),
                        TEST_DEPENDENCY_MARKER=str(self.root / "dependencies-installed"))

    def invoke(self, *args, fail_mode="", missing_dependency="", apt_failure=""):
        return subprocess.run(["bash", str(self.bin / "omarchy-install-playwright-browser"), *args],
                              env=dict(self.env, TEST_FAIL_MODE=fail_mode, TEST_MISSING_DEPENDENCY=missing_dependency,
                                       TEST_APT_FAILURE=apt_failure), capture_output=True, text=True)

    def test_explicit_firefox_fallback_is_published_after_both_probes(self):
        result = self.invoke("firefox", missing_dependency="all")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([json.loads(line) for line in self.calls.read_text().splitlines()], [
            ["install", "firefox"], ["probe", "firefox", "headless"], ["probe", "firefox", "wayland"],
        ])
        entry = (self.apps / "Firefox-Playwright.desktop").read_text()
        self.assertIn("Exec=/usr/local/bin/omarchy-playwright-firefox %u", entry)
        self.assertIn("Firefox (Playwright)", entry)
        self.assertFalse(self.apt_calls.exists())

    def test_chromium_probe_failure_removes_legacy_fake_entry_and_propagates_failure(self):
        self.apps.mkdir(parents=True)
        (self.apps / "Chromium.desktop").write_text("[Desktop Entry]\nExec=/usr/local/bin/omarchy-chromium\n")
        result = self.invoke("chromium", fail_mode="headless")
        self.assertEqual(result.returncode, 27)
        self.assertFalse((self.apps / "Chromium.desktop").exists())
        self.assertFalse((self.apps / "Chromium-Playwright.desktop").exists())
        self.assertNotIn("✓", result.stdout)

    def test_headless_only_success_does_not_publish_a_wayland_entry(self):
        result = self.invoke("firefox", fail_mode="wayland")
        self.assertEqual(result.returncode, 27)
        self.assertFalse((self.apps / "Firefox-Playwright.desktop").exists())
        self.assertNotIn("✓", result.stdout)

    def test_chromium_uses_only_bundled_128_validation_and_both_probes(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([json.loads(line) for line in self.calls.read_text().splitlines()], [
            ["check"], ["probe", "chromium", "headless"], ["probe", "chromium", "wayland"],
        ])
        entry = (self.apps / "Chromium-Playwright.desktop").read_text()
        self.assertIn("Chromium 128", entry)
        self.assertIn("Exec=/usr/local/bin/omarchy-playwright-chromium %u", entry)
        agent = (self.apps / "Chromium-128-Agent.desktop").read_text()
        self.assertIn("Exec=/usr/local/bin/omarchy-playwright-chromium --agent %u", agent)
        self.assertFalse(self.apt_calls.exists(), "Installed NSS packages must not trigger APT")

    def test_failed_chromium_wayland_probe_does_not_publish_entry(self):
        result = self.invoke("chromium", fail_mode="wayland")
        self.assertEqual(result.returncode, 27)
        self.assertFalse((self.apps / "Chromium-Playwright.desktop").exists())
        self.assertFalse((self.apps / "Chromium-128-Agent.desktop").exists())

    def test_old_guest_missing_one_nss_package_installs_only_system_dependencies(self):
        result = self.invoke("chromium", missing_dependency="libnspr4")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([json.loads(line) for line in self.apt_calls.read_text().splitlines()], [
            ["update"], ["install", "-y", "--no-install-recommends", "libnss3", "libnspr4"],
        ])
        self.assertEqual([json.loads(line) for line in self.calls.read_text().splitlines()], [
            ["check"], ["probe", "chromium", "headless"], ["probe", "chromium", "wayland"],
        ])

    def test_dependency_update_or_install_failure_stops_before_runtime_probes(self):
        for stage in ("update", "install"):
            with self.subTest(stage=stage):
                self.apt_calls.unlink(missing_ok=True)
                result = self.invoke("chromium", missing_dependency="all", apt_failure=stage)
                self.assertEqual(result.returncode, 42)
                self.assertFalse(self.calls.exists())
                self.assertFalse((self.apps / "Chromium-Playwright.desktop").exists())
                self.assertFalse((self.apps / "Chromium-128-Agent.desktop").exists())
                self.assertNotIn("✓", result.stdout)


class BrowserMenuTests(unittest.TestCase):
    def test_menu_names_actual_engines_and_marks_unsupported_brands(self):
        loader = importlib.machinery.SourceFileLoader("omarchy_menu_browser_test", str(BIN / "omarchy-menu-ui"))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        module = importlib.util.module_from_spec(spec)
        loader.exec_module(module)
        rows = module.browser_menu_entries([
            ("install.browser.chromium", {"label": "Chromium"}),
            ("install.browser.firefox", {"label": "Firefox"}),
            ("install.browser.brave", {"label": "Brave"}),
        ])
        entries = dict(rows)
        self.assertEqual(len(entries), len(rows))
        self.assertIn("WebKitGTK", entries["install.browser.omarchy"]["label"])
        self.assertIn("Chromium 128", entries["install.browser.chromium"]["label"])
        self.assertIn("Mozilla DEB", entries["install.browser.firefox"]["label"])
        self.assertIn("Agent", entries["install.browser.playwright"]["label"])
        self.assertIn("Chromium 128", entries["install.browser.playwright"]["label"])
        self.assertIn("Firefox", entries["install.browser.firefox-playwright"]["label"])
        self.assertIn("按需回退", entries["install.browser.firefox-playwright"]["label"])
        self.assertLess([key for key, _ in rows].index("install.browser.playwright"),
                        [key for key, _ in rows].index("install.browser.chromium"))
        self.assertIn("未适配", entries["install.browser.brave"]["label"])

    def test_explicit_agent_menu_starts_only_the_isolated_cdp_launcher(self):
        loader = importlib.machinery.SourceFileLoader("omarchy_menu_agent_test", str(BIN / "omarchy-menu-ui"))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        module = importlib.util.module_from_spec(spec)
        loader.exec_module(module)
        with patch.object(module, "kill_wofi"), patch.object(module, "wl_env", return_value={"WAYLAND_DISPLAY": "fixture"}), \
                patch.object(module.subprocess, "Popen") as launch:
            self.assertTrue(module.handle_special_route("install.browser.chromium-agent"))
        launch.assert_called_once_with(["/usr/local/bin/omarchy-playwright-chromium", "--agent"],
                                       env={"WAYLAND_DISPLAY": "fixture"})


if __name__ == "__main__":
    unittest.main()
