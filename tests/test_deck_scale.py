# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise Deck scale commands against real subprocess and filesystem boundaries."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "deck_scale_bridge",
    Path(__file__).resolve().parents[1] / "core/bin/omarchy-deck-bridge.py",
)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class DeckScale(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.calls = self.root / "sway-calls.jsonl"
        self.scale_json = self.root / "config/omarchy/display_scale.json"
        self.scale_legacy = self.root / "config/omarchy/display-scale"
        swaymsg = self.root / "swaymsg"
        swaymsg.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "with open(os.environ['SWAY_CALLS'], 'a') as stream:\n"
            "    stream.write(json.dumps({'args': sys.argv[1:], "
            "'socket': os.environ.get('SWAYSOCK')}) + '\\n')\n"
            "sys.exit(int(os.environ.get('SWAY_EXIT', '0')))\n"
        )
        swaymsg.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": str(self.root),
            "SWAYSOCK": "fixture-sway-session",
            "SWAY_CALLS": str(self.calls),
        }
        for name, value in (
            ("SCALE_JSON_FILE", str(self.scale_json)),
            ("SCALE_FILE", str(self.scale_legacy)),
            ("wl_env", lambda: self.environment),
        ):
            context = patch.object(bridge, name, value, create=True)
            context.start()
            self.addCleanup(context.stop)

    def saved_scale(self):
        return self.scale_json.read_bytes(), self.scale_legacy.read_bytes()

    def seed_scale(self):
        self.scale_json.parent.mkdir(parents=True)
        self.scale_json.write_bytes(b"1.25")
        self.scale_legacy.write_bytes(b"1.25")

    def test_each_supported_scale_reaches_sway_and_both_saved_formats(self):
        for value, saved in (
            ("0.85", b"0.85"),
            ("1.0", b"1.00"),
            ("1.25", b"1.25"),
            ("1.5", b"1.50"),
            ("1.75", b"1.75"),
            ("2.0", b"2.00"),
        ):
            with self.subTest(value=value):
                bridge.handle_cmd({"cmd": "scale_" + value})
                self.assertEqual(self.saved_scale(), (saved, saved))
                self.assertEqual(json.loads(self.scale_json.read_text()), float(value))
        self.assertEqual(
            [json.loads(line) for line in self.calls.read_text().splitlines()],
            [
                {"args": ["output", "X11-1", "scale", "0.85"], "socket": "fixture-sway-session"},
                {"args": ["output", "X11-1", "scale", "1.0"], "socket": "fixture-sway-session"},
                {"args": ["output", "X11-1", "scale", "1.25"], "socket": "fixture-sway-session"},
                {"args": ["output", "X11-1", "scale", "1.5"], "socket": "fixture-sway-session"},
                {"args": ["output", "X11-1", "scale", "1.75"], "socket": "fixture-sway-session"},
                {"args": ["output", "X11-1", "scale", "2.0"], "socket": "fixture-sway-session"},
            ],
        )

    def test_rejects_noncanonical_values_and_command_injection_without_side_effects(self):
        self.seed_scale()
        for value in (
            "", "1", "1.00", "1.250", "1.50", " 1.25", "1.25 ",
            "0.70", "2.5", "nan", "inf", "1.25; touch /tmp/deck-injected",
            "1.25\nfullscreen toggle", "1.25' fullscreen toggle '",
            "$(touch /tmp/deck-injected)", "1.25, output * disable",
        ):
            with self.subTest(value=value):
                bridge.handle_cmd({"cmd": "scale_" + value})
                self.assertEqual(self.saved_scale(), (b"1.25", b"1.25"))
                self.assertFalse(self.calls.exists())

    def test_failed_sway_command_preserves_previously_saved_scale(self):
        self.seed_scale()
        self.environment["SWAY_EXIT"] = "2"
        with self.assertRaises(subprocess.CalledProcessError):
            bridge.handle_cmd({"cmd": "scale_1.5"})
        self.assertEqual(self.saved_scale(), (b"1.25", b"1.25"))

    def test_missing_swaymsg_does_not_create_saved_scale(self):
        (self.root / "swaymsg").unlink()
        with self.assertRaises(FileNotFoundError):
            bridge.handle_cmd({"cmd": "scale_1.5"})
        self.assertFalse(self.scale_json.exists())
        self.assertFalse(self.scale_legacy.exists())

    def test_failed_persistence_preserves_saved_files_and_removes_temporary_files(self):
        self.seed_scale()
        with patch.object(bridge.os, "replace", side_effect=OSError("read-only filesystem")):
            with self.assertRaisesRegex(OSError, "read-only filesystem"):
                bridge.handle_cmd({"cmd": "scale_1.5"})
        self.assertEqual(self.saved_scale(), (b"1.25", b"1.25"))
        self.assertEqual(
            sorted(path.name for path in self.scale_json.parent.iterdir()),
            ["display-scale", "display_scale.json"],
        )
