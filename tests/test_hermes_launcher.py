"""Exercise Hermes' keyboard recovery hook with an inert local executable."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


class HermesLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes launcher ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.launcher = self.root / "hermes-desktop-launch"
        original = (REPO / "core/ai-suite/hermes-desktop-launch").read_text()
        self.assertEqual(original.count("exec /opt/hermes-desktop/Hermes "), 1)
        self.launcher.write_text(original.replace("exec /opt/hermes-desktop/Hermes ", 'exec "$TEST_HERMES_BINARY" '))
        self.events = self.root / "events"
        self.arguments = self.root / "arguments.json"
        binary = self.root / "inert-hermes"
        binary.write_text('#!' + sys.executable + '\nimport json,os,sys\n'
                          'with open(os.environ["TEST_EVENTS"],"a") as out: out.write("launch\\n")\n'
                          'open(os.environ["TEST_ARGUMENTS"],"w").write(json.dumps(sys.argv[1:]))\n')
        binary.chmod(0o755)
        self.env = dict(os.environ, TEST_EVENTS=str(self.events), TEST_ARGUMENTS=str(self.arguments),
                        TEST_HERMES_BINARY=str(binary))

    def helper(self, status):
        helper = self.root / "omarchy-restore-x11-keyboard"
        helper.write_text('#!/bin/sh\nprintf "restore\\n" >> "$TEST_EVENTS"\nexit ' + str(status) + '\n')
        helper.chmod(0o755)

    def invoke(self, *args):
        return subprocess.run(["bash", str(self.launcher), *args], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def test_restore_failure_prevents_hermes_exec_and_preserves_failure(self):
        self.helper(37)
        result = self.invoke("literal argument")
        self.assertEqual(result.returncode, 37)
        self.assertEqual(self.events.read_text().splitlines(), ["restore"])
        self.assertFalse(self.arguments.exists())
        self.assertIn("无法恢复 X11 键盘状态", result.stderr)

    def test_restore_precedes_exec_and_url_is_preserved(self):
        self.helper(0)
        url = "https://example.test/?text=中文&literal=$(no-execution)"
        result = self.invoke(url)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.events.read_text().splitlines(), ["restore", "launch"])
        self.assertEqual(json.loads(self.arguments.read_text())[-1], url)

    def test_older_install_without_helper_can_launch(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.events.read_text().splitlines(), ["launch"])


if __name__ == "__main__":
    unittest.main()
