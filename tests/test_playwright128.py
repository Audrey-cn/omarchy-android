"""Exercise the fixed Chromium entry and its GUI/CDP isolation without a device."""
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


@unittest.skipUnless(NODE, "Node is needed to exercise the pinned SDK interface")
class Playwright128Tests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pinned chromium ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("omarchy-playwright128", "omarchy-playwright128-check.cjs",
                     "omarchy-playwright-chromium", "omarchy-playwright-tool.cjs"):
            shutil.copy2(BIN / name, self.bin / name)
        self.home = self.root / "pinned browser"
        self.home.mkdir()
        source = json.loads((REPO / "scripts/browser-bundle-sources.json").read_text())
        (self.home / "source-manifest.json").write_text(json.dumps(source))
        for name in ("playwright", "playwright-core"):
            package = self.home / "node_modules" / name
            package.mkdir(parents=True)
            (package / "package.json").write_text(json.dumps({
                "name": name, "version": "1.46.1", "main": "index.js",
                "dependencies": {"playwright-core": "1.46.1"},
            }))
        (self.home / "node_modules/playwright-core/browsers.json").write_text(json.dumps({"browsers": [
            {"name": "chromium", "revision": "1129", "browserVersion": "128.0.6613.18"}
        ]}))
        package = self.home / "node_modules/playwright"
        (package / "index.js").write_text('''
module.exports={chromium:{
  executablePath:()=>process.env.TEST_BROWSER,
  launch:async()=>{throw new Error('pinned fixture startup failed')},
}};
''')
        (package / "cli.js").write_text('''
require('fs').writeFileSync(process.env.TEST_ARGUMENTS,JSON.stringify({
 args:process.argv.slice(2),cache:process.env.PLAYWRIGHT_BROWSERS_PATH}));
''')
        self.browser = self.home / "browsers/chromium-1129/chrome-linux/chrome"
        self.browser.parent.mkdir(parents=True)
        self.browser.write_text('#!' + sys.executable + '\nimport json,os,sys\nopen(os.environ["TEST_ARGUMENTS"],"w").write(json.dumps(sys.argv[1:]))\n')
        self.browser.chmod(0o755)
        node_home = self.root / "node"
        self.node = node_home / "node-v24.15.0-linux-arm64/bin/node"
        self.node.parent.mkdir(parents=True)
        self.node.write_text('''#!/bin/sh
if [ "$1" = --version ]; then printf 'v%s\\n' "${TEST_NODE_VERSION:-24.15.0}"; exit 0; fi
exec "$TEST_NODE" "$@"
''')
        self.node.chmod(0o755)
        self.arguments = self.root / "arguments.json"
        self.results = self.root / "results"
        self.env = dict(os.environ, HOME=str(self.root), TEST_NODE=NODE, TEST_BROWSER=str(self.browser),
                        TEST_ARGUMENTS=str(self.arguments), OMARCHY_NODE_HOME=str(node_home),
                        OMARCHY_PLAYWRIGHT128_HOME=str(self.home),
                        OMARCHY_PLAYWRIGHT128_RESULTS=str(self.results),
                        # The new entry must ignore the legacy SDK/cache setting.
                        OMARCHY_PLAYWRIGHT_HOME=str(self.root / "wrong legacy sdk"))

    def invoke(self, *args, launcher=False, env=None):
        command = self.bin / ("omarchy-playwright-chromium" if launcher else "omarchy-playwright128")
        return subprocess.run(["bash", str(command), *args], env=env or self.env,
                              cwd=self.root, capture_output=True, text=True)

    def test_fixed_sdk_and_browser_path_ignore_the_legacy_install(self):
        result = self.invoke("browser-path", "chromium")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, str(self.browser) + "\n")
        result = self.invoke("run", "-e", "console.log(require('playwright/package.json').version)")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "1.46.1\n")

    def test_incomplete_or_different_fixed_runtime_fails_without_download(self):
        self.node.unlink()
        result = self.invoke("browser-path", "chromium")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.arguments.exists())
        self.assertIn("更新 Omarchy Android 一体包", result.stderr)

    def test_changed_node_sdk_or_source_receipt_is_rejected(self):
        result = self.invoke("check", env=dict(self.env, TEST_NODE_VERSION="25.0.0"))
        self.assertNotEqual(result.returncode, 0)
        package = self.home / "node_modules/playwright-core/package.json"
        original = package.read_bytes()
        package.write_text('{"version":"1.65.1"}')
        self.assertNotEqual(self.invoke("check").returncode, 0)
        package.write_bytes(original)
        receipt = self.home / "source-manifest.json"
        data = json.loads(receipt.read_text())
        data["sources"][0]["sha256"] = "0" * 64
        receipt.write_text(json.dumps(data))
        self.assertNotEqual(self.invoke("check").returncode, 0)
        self.assertFalse(self.arguments.exists())

    def test_cli_keeps_arguments_and_never_runs_install_commands(self):
        result = self.invoke("cli", "--version")
        self.assertEqual(result.returncode, 0, result.stderr)
        value = json.loads(self.arguments.read_text())
        self.assertEqual(value["args"], ["--version"])
        self.assertEqual(value["cache"], str(self.home / "browsers"))
        self.arguments.unlink()
        for args in [("cli", "install", "chromium"), ("cli", "--", "install", "chromium"),
                     ("cli", "install-deps"), ("cli", "uninstall")]:
            with self.subTest(args=args):
                self.assertNotEqual(self.invoke(*args).returncode, 0)
                self.assertFalse(self.arguments.exists())

    def test_normal_gui_keeps_literal_arguments_and_opens_no_cdp(self):
        url = 'https://example.test/?q=中文&literal=$(touch nope);x="a b"'
        result = self.invoke("--app=" + url, "--new-window", launcher=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.arguments.read_text())
        self.assertEqual(args[-2:], ["--app=" + url, "--new-window"])
        self.assertIn("--user-data-dir=" + str(self.root / ".local/share/omarchy-chromium128/desktop"), args)
        self.assertIn("--ozone-platform=wayland", args)
        self.assertFalse(any(arg.startswith("--remote-debugging") for arg in args))
        self.assertFalse((self.root / "nope").exists())
        self.assertFalse(any("gpu" in arg or "single-process" in arg for arg in args))

    def test_explicit_agent_uses_separate_profile_and_only_loopback_9222(self):
        result = self.invoke("--agent", "https://example.test", launcher=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.arguments.read_text())
        self.assertIn("--user-data-dir=" + str(self.root / ".local/share/omarchy-chromium128/agent"), args)
        self.assertIn("--remote-debugging-address=127.0.0.1", args)
        self.assertIn("--remote-debugging-port=9222", args)
        self.assertEqual(args[-1], "https://example.test")
        self.assertNotIn("--agent", args)

    def test_daily_proot_entry_launches_the_real_pinned_browser(self):
        daily = self.bin / "omarchy-chromium"
        shutil.copy2(BIN / "omarchy-chromium", daily)
        url = "--app=https://example.test/?text=中文&x=2"
        result = subprocess.run(["bash", str(daily), url], env=self.env,
                                cwd=self.root, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads(self.arguments.read_text())
        self.assertEqual(args[-1], url)
        self.assertFalse(any(arg.startswith("--remote-debugging") for arg in args))

    def test_callers_cannot_override_cdp_profile_or_graphical_mode(self):
        for args in [("--remote-debugging-port=9333",), ("--remote-debugging-address", "0.0.0.0"),
                     (" --remote-debugging-port=9333",), ("\t-user-data-dir=/tmp/shared",),
                     ("\n\r--remote-debugging-port=9333\v\f",),
                     ("-remote-debugging-port=9333",), ("-remote-debugging-address", "0.0.0.0"),
                     ("--agent", "--remote-debugging-address=0.0.0.0"),
                     ("--agent", "-remote-debugging-address=0.0.0.0"),
                     ("--user-data-dir=/tmp/shared",), ("--profile-directory=Default",),
                     ("-user-data-dir=/tmp/shared",), ("-profile-directory=Default",),
                     ("--agent", "--remote-allow-origins=*"), ("--agent", "-remote-allow-origins=*"),
                     ("--headless",), ("-headless",), ("--ozone-platform=x11",), ("-ozone-platform=x11",)]:
            with self.subTest(args=args):
                self.assertEqual(self.invoke(*args, launcher=True).returncode, 2)
                self.assertFalse(self.arguments.exists())

    def test_probe_failure_is_reported_by_the_fixed_runtime(self):
        result = self.invoke("probe", "chromium", "headless")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        report = json.loads(next(self.results.glob("*/result.json")).read_text())
        self.assertEqual(report["playwright"], "1.46.1")
        self.assertEqual(report["status"], "failed")
        self.assertIn("pinned fixture startup failed", report["error"])

    def keyboard_helper(self, status):
        marker = self.root / "keyboard-restored"
        helper = self.bin / "omarchy-restore-x11-keyboard"
        helper.write_text('#!/bin/sh\nprintf restore > "$TEST_KEYBOARD_MARKER"\nexit ' + str(status) + '\n')
        helper.chmod(0o755)
        self.env["TEST_KEYBOARD_MARKER"] = str(marker)
        return marker

    def test_gui_keyboard_restore_failure_prevents_chromium_launch(self):
        marker = self.keyboard_helper(37)
        result = self.invoke("--agent", "https://example.test", launcher=True)
        self.assertEqual(result.returncode, 37)
        self.assertTrue(marker.exists())
        self.assertFalse(self.arguments.exists())
        self.assertIn("无法恢复 X11 键盘状态", result.stderr)

    def test_only_wayland_probe_restores_keyboard_and_failure_stops_the_probe(self):
        marker = self.keyboard_helper(37)
        result = self.invoke("probe", "chromium", "wayland")
        self.assertEqual(result.returncode, 37)
        self.assertTrue(marker.exists())
        self.assertFalse(self.results.exists())
        self.assertIn("无法恢复 X11 键盘状态", result.stderr)
        marker.unlink()
        result = self.invoke("probe", "chromium", "headless")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists(), "Headless must not reconfigure the active keyboard")
        report = json.loads(next(self.results.glob("*/result.json")).read_text())
        self.assertIn("pinned fixture startup failed", report["error"])

    def test_successful_keyboard_restore_happens_before_gui_browser_exec(self):
        marker = self.keyboard_helper(0)
        self.browser.write_text('#!' + sys.executable + '\nimport os,sys\nfrom pathlib import Path\n'
                                'sys.exit(0 if Path(os.environ["TEST_KEYBOARD_MARKER"]).is_file() else 99)\n')
        result = self.invoke("https://example.test", launcher=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(marker.exists())


if __name__ == "__main__":
    unittest.main()
