# SPDX-License-Identifier: GPL-3.0-or-later
"""Verify managed APK payload construction without a rootfs or Android SDK."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/prepare-android-managed.py"


class AndroidManagedAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="omarchy managed assets ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "checkout"
        (self.repo / "core/bin").mkdir(parents=True)
        (self.repo / "core/ai-suite").mkdir()
        (self.repo / "core/bin/omarchy-one").write_text("#!/bin/sh\necho current\n")
        (self.repo / "core/bin/notes").write_text("not an executable source\n")
        (self.repo / "core/ai-suite/agent-launch").write_text("#!/bin/sh\necho agent\n")
        for args in (("init", "-q"), ("config", "user.email", "fixture@example.invalid"),
                     ("config", "user.name", "Fixture"), ("add", "."), ("commit", "-qm", "source")):
            subprocess.run(["git", "-C", str(self.repo), *args], check=True)
        self.sha = subprocess.check_output(["git", "-C", str(self.repo), "rev-parse", "HEAD"], text=True).strip()
        self.output = self.root / "generated"

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--repo", str(self.repo),
                               "--output-dir", str(self.output), *args], capture_output=True, text=True)

    def extra(self, path="opt/omarchy-node/version/bin/node", contents=b"node fixture"):
        directory = self.root / "extra"
        payload = directory / "managed-core/files" / path
        payload.parent.mkdir(parents=True, exist_ok=True)
        payload.write_bytes(contents)
        manifest = dict(schemaVersion=1, sourceSha=self.sha, files=[dict(
            path=path, sha256=hashlib.sha256(contents).hexdigest(), mode=493)])
        (directory / "managed-core/manifest.json").write_text(json.dumps(manifest))
        return directory, payload

    def test_current_core_only_has_deterministic_hashes_and_no_user_configuration(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest_path = self.output / "managed-core/manifest.json"
        first = manifest_path.read_bytes()
        manifest = json.loads(first)
        self.assertEqual(manifest["sourceSha"], self.sha)
        self.assertEqual({entry["path"] for entry in manifest["files"]},
                         {"usr/local/bin/omarchy-one", "usr/local/bin/agent-launch"})
        for entry in manifest["files"]:
            payload = self.output / "managed-core/files" / entry["path"]
            self.assertEqual(hashlib.sha256(payload.read_bytes()).hexdigest(), entry["sha256"])
            self.assertEqual(entry["mode"], 493)
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(manifest_path.read_bytes(), first)

    def test_optional_verified_payload_is_merged(self):
        extra, payload = self.extra()
        result = self.run_script("--extra-dir", str(extra))
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((self.output / "managed-core/manifest.json").read_text())
        self.assertEqual(len(manifest["files"]), 3)
        self.assertEqual((self.output / "managed-core/files/opt/omarchy-node/version/bin/node").read_bytes(), payload.read_bytes())

    def test_bad_extra_hash_leaves_previous_good_output_unchanged(self):
        self.assertEqual(self.run_script().returncode, 0)
        manifest_path = self.output / "managed-core/manifest.json"
        previous = manifest_path.read_bytes()
        extra, payload = self.extra()
        payload.write_bytes(b"corrupt")
        result = self.run_script("--extra-dir", str(extra))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256", result.stderr)
        self.assertEqual(manifest_path.read_bytes(), previous)

    def test_extra_symlink_and_unapproved_paths_are_rejected(self):
        extra, payload = self.extra()
        outside = self.root / "outside"
        outside.write_bytes(payload.read_bytes())
        payload.unlink()
        payload.symlink_to(outside)
        self.assertNotEqual(self.run_script("--extra-dir", str(extra)).returncode, 0)
        payload.unlink()
        payload.write_bytes(outside.read_bytes())
        manifest_path = extra / "managed-core/manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][0]["path"] = "root/.hermes/config.json"
        manifest_path.write_text(json.dumps(manifest))
        rejected = self.run_script("--extra-dir", str(extra))
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("Unapproved", rejected.stderr)

    def test_output_directory_without_generator_marker_is_never_removed(self):
        self.output.mkdir()
        sentinel = self.output / "personal"
        sentinel.write_text("keep")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(sentinel.read_text(), "keep")


if __name__ == "__main__":
    unittest.main()
