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

    def test_desktop_launcher_passes_url_without_reparsing_shell(self):
        url = "https://example.test/?q=中文&second=2"
        result = subprocess.run(["bash", str(self.bin / "omarchy-playwright-chromium"), url],
                                env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.args_file.read_text())
        self.assertEqual(args[-1], url)
        self.assertIn("--ozone-platform=wayland", args)
        self.assertNotIn("--single-process", args)
        self.assertNotIn("--in-process-gpu", args)

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
''')
        stub.chmod(0o755)
        self.calls = self.root / "calls.jsonl"
        self.apps = self.root / ".local/share/applications"
        self.env = dict(os.environ, HOME=str(self.root), TEST_CALLS=str(self.calls))

    def invoke(self, *args, fail_mode=""):
        return subprocess.run(["bash", str(self.bin / "omarchy-install-playwright-browser"), *args],
                              env=dict(self.env, TEST_FAIL_MODE=fail_mode), capture_output=True, text=True)

    def test_default_firefox_entry_is_published_after_both_probes(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([json.loads(line) for line in self.calls.read_text().splitlines()], [
            ["install", "firefox"], ["probe", "firefox", "headless"], ["probe", "firefox", "wayland"],
        ])
        entry = (self.apps / "Firefox-Playwright.desktop").read_text()
        self.assertIn("Exec=/usr/local/bin/omarchy-playwright-firefox %u", entry)
        self.assertIn("Firefox (Playwright)", entry)

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
        self.assertIn("Playwright", entries["install.browser.chromium"]["label"])
        self.assertIn("Mozilla DEB", entries["install.browser.firefox"]["label"])
        self.assertIn("Agent", entries["install.browser.playwright"]["label"])
        self.assertIn("Firefox", entries["install.browser.playwright"]["label"])
        self.assertIn("实验性", entries["install.browser.chromium"]["label"])
        self.assertLess([key for key, _ in rows].index("install.browser.playwright"),
                        [key for key, _ in rows].index("install.browser.chromium"))
        self.assertIn("未适配", entries["install.browser.brave"]["label"])


if __name__ == "__main__":
    unittest.main()
