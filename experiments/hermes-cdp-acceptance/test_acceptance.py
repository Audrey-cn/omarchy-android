"""Offline checks only: importing this harness never imports or calls Hermes."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("hermes_cdp_acceptance", Path(__file__).with_name("acceptance.py"))
ACCEPTANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACCEPTANCE)
NAME = "hermes-fixture-" + "a" * 32 + ".txt"


class OfflineAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="hermes-cdp-offline-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def test_unique_target_and_exact_controlled_payload(self):
        directory, first = ACCEPTANCE.prepare_download_target(self.root / "Downloads")
        _, second = ACCEPTANCE.prepare_download_target(directory)
        self.assertNotEqual(first, second)
        self.assertFalse(first.exists())
        self.assertFalse(second.exists())
        self.assertEqual(len(ACCEPTANCE.DOWNLOAD), 55)
        self.assertEqual(ACCEPTANCE.hash_bytes(ACCEPTANCE.DOWNLOAD),
                         "64f3919c426249f095f0b323779de24928383d8265037112770917101fdab1ef")

    def test_old_file_cannot_count_as_new_download(self):
        target = self.root / NAME
        target.write_bytes(ACCEPTANCE.DOWNLOAD)
        with self.assertRaisesRegex(ValueError, "download_target_preexists"):
            ACCEPTANCE.prepare_download_target(self.root, NAME)
        self.assertEqual(target.read_bytes(), ACCEPTANCE.DOWNLOAD)

    def test_dangling_target_symlink_is_not_absent(self):
        (self.root / NAME).symlink_to(self.root / "missing")
        with self.assertRaisesRegex(ValueError, "download_target_preexists"):
            ACCEPTANCE.prepare_download_target(self.root, NAME)

    def test_directory_symlink_is_rejected(self):
        directory = self.root / "Downloads"
        directory.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "download_directory_not_real_directory"):
            ACCEPTANCE.prepare_download_target(directory)

    def test_non_fixture_names_are_rejected(self):
        for name in ("hermes-fixture.txt", "../outside.txt", "/tmp/outside.txt"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "invalid_fixture_download_name"):
                ACCEPTANCE.prepare_download_target(self.root, name)

    def test_download_callback_retains_only_allowlisted_fields(self):
        expected = self.root / "Downloads"
        arguments = {"method": "Browser.setDownloadBehavior", "params": {
            "behavior": "allow", "downloadPath": str(expected),
            "browserContextId": "private-context", "api_key": "secret-not-for-receipt",
        }, "url": "https://private.example.invalid"}
        self.assertEqual(ACCEPTANCE.safe_download_behavior(arguments, expected), {
            "behavior": "allow", "downloadPath": str(expected), "downloadPathMatchesExpected": True,
        })
        arguments["params"].update(behavior="private-behavior", downloadPath="/private/path")
        safe = ACCEPTANCE.safe_download_behavior(arguments, expected)
        self.assertEqual(safe, {"behavior": "invalid-or-missing", "downloadPath": "other-redacted",
                                "downloadPathMatchesExpected": False})
        for private in ("private-context", "secret-not-for-receipt", "private.example", "/private/path"):
            self.assertNotIn(private, json.dumps(safe))

    def test_other_cdp_parameters_are_not_retained(self):
        self.assertIsNone(ACCEPTANCE.safe_download_behavior({
            "method": "Runtime.evaluate", "params": {"expression": "private-script"},
        }, self.root))
        self.assertEqual(ACCEPTANCE.safe_download_behavior({"method": "Browser.setDownloadBehavior"}, self.root), {
            "behavior": "invalid-or-missing", "downloadPath": "missing", "downloadPathMatchesExpected": False,
        })

    def test_private_artifact_write_never_overwrites_existing_file(self):
        target = self.root / "artifact"
        ACCEPTANCE.private_write(target, b"first")
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError):
            ACCEPTANCE.private_write(target, b"replacement")
        self.assertEqual(target.read_bytes(), b"first")


if __name__ == "__main__":
    unittest.main()
