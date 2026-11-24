"""Dock process ownership, Sway integration, and phone-safe bar defaults."""

import builtins
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, relative):
    path = ROOT / relative
    assert path.is_file(), f"missing desktop command: {relative}"
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class DockTests(unittest.TestCase):
    def test_bar_inset_defaults_to_30_and_preserves_saved_values_with_64px_cap(self):
        engine = load("dock_theme", "core/bin/omarchy-theme-engine.py")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bar-inset"
            self.assertEqual(engine.load_bar_inset(path), 30)
            for saved, expected in [("32", 32), ("56", 56), ("100", 64), ("-1", 0), ("bad", 30)]:
                path.write_text(saved)
                self.assertEqual(engine.load_bar_inset(path), expected)

    def test_dock_collects_tiled_and_floating_sway_windows_without_containers(self):
        dock = load("dock_windows", "core/bin/omarchy-dock")
        tree = {"nodes": [{"id": 1, "type": "workspace", "name": "1", "nodes": [
            {"id": 42, "type": "con", "app_id": "Alacritty", "name": "Terminal", "focused": True},
            {"id": 43, "type": "con", "name": "empty split", "nodes": []},
        ]}], "floating_nodes": [{"id": 99, "type": "con", "name": "Files", "window_properties": {"class": "Thunar"}}]}
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            swaymsg = tmp_path / "swaymsg"
            swaymsg.write_text(
                '#!/bin/sh\n[ "$1" = "-t" ] && [ "$2" = "get_tree" ] || exit 2\n'
                "printf '%s\\n' " + shlex.quote(json.dumps(tree)) + "\n"
            )
            swaymsg.chmod(0o755)
            check_output = subprocess.check_output

            def traced_check_output(*args, **kwargs):
                self.assertEqual(kwargs["timeout"], 1)
                return check_output(*args, **{**kwargs, "timeout": 5})

            with patch.dict(os.environ, {"PATH": str(tmp_path)}):
                with patch.object(dock.subprocess, "check_output", side_effect=traced_check_output):
                    windows = dock.sway_windows(dict(os.environ))
            self.assertEqual([(w["id"], w["name"]) for w in windows], [(42, "Terminal"), (99, "Files")])
            self.assertTrue(windows[0]["focused"])
            self.assertEqual(dock.sway_windows({"PATH": str(tmp_path / "missing")}), [])

    def test_dock_unavailable_or_malformed_sway_ipc_returns_empty_list(self):
        dock = load("dock_failed_ipc", "core/bin/omarchy-dock")
        for response in [subprocess.TimeoutExpired(["swaymsg", "-t", "get_tree"], 1), "{invalid json"]:
            with self.subTest(response=response):
                boundary = {"side_effect": response} if isinstance(response, Exception) else {"return_value": response}
                with patch.object(dock.subprocess, "check_output", **boundary) as ipc:
                    self.assertEqual(dock.sway_windows({"SWAYSOCK": "fixture-sway"}), [])
                self.assertEqual(ipc.call_args.kwargs["timeout"], 1)

    def test_dock_toggle_starts_then_closes_and_reclaims_stale_pid(self):
        dock_path = ROOT / "core/bin/omarchy-dock"
        self.assertTrue(dock_path.is_file(), "missing dock implementation")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            pidfile = tmp_path / "omarchy-dock.pid"
            ready = tmp_path / "ready"
            script = """import importlib.machinery, importlib.util, signal, sys, time
loader = importlib.machinery.SourceFileLoader('dock', sys.argv[1])
spec = importlib.util.spec_from_loader('dock', loader)
dock = importlib.util.module_from_spec(spec)
loader.exec_module(dock)
def close(*_):
    raise SystemExit(0)
def ui():
    signal.signal(signal.SIGTERM, close)
    open(sys.argv[3], 'w').write('ready')
    while True:
        time.sleep(0.02)
dock.run_dock = ui
raise SystemExit(dock.toggle(sys.argv[2]))
"""
            def invoke():
                return subprocess.Popen([sys.executable, "-c", script, str(dock_path), str(pidfile), str(ready)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

            pidfile.write_text(str(os.getpid()))
            first = invoke()
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and first.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists())
                self.assertEqual(pidfile.read_text().strip(), str(first.pid))
                lockfile = Path(str(pidfile) + ".lock")
                lifetime_inode = lockfile.stat().st_ino
                second = invoke()
                _, error = second.communicate(timeout=5)
                self.assertEqual(second.returncode, 0, error)
                _, error = first.communicate(timeout=5)
                self.assertEqual(first.returncode, 0, error)
                self.assertFalse(pidfile.exists())
                self.assertEqual(lockfile.stat().st_ino, lifetime_inode)
            finally:
                if first.poll() is None:
                    first.terminate()
                    first.communicate(timeout=5)

    def test_dock_pins_core_apps_and_is_executable(self):
        dock = load("dock_pins", "core/bin/omarchy-dock")
        self.assertEqual([entry[0] for entry in dock.PINNED_APPS], ["Apps Grid", "Terminal", "Browser", "Files", "Settings / Menu"])
        self.assertTrue(os.access(ROOT / "core/bin/omarchy-dock", os.X_OK))

    def test_generated_sway_dock_bindings_follow_modifier_mode(self):
        for mode, primary, alt_mirror in [("super", "Mod4", False), ("alt", "Mod1", False), ("super+alt", "Mod4", True)]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                engine = load("dock_theme_generation", "core/bin/omarchy-theme-engine.py")

                def mapped(path):
                    value = str(path)
                    if value.startswith("/root/") or value == "/tmp/omarchy-sway.conf":
                        return tmp_path / value.lstrip("/")
                    return path

                (tmp_path / "themes/tokyo-night").mkdir(parents=True)
                config = tmp_path / "root/.config/omarchy"
                config.mkdir(parents=True)
                (config / "modifier.json").write_text(json.dumps({"mode": mode}))
                (tmp_path / "tmp").mkdir()
                engine.OMARCHY_PATH = str(tmp_path)
                engine.STATE_DIR = str(tmp_path / "state")
                engine.THEME_DIR = str(tmp_path / "state/theme")
                engine.os = SimpleNamespace(
                    environ={},
                    path=SimpleNamespace(exists=lambda path: os.path.exists(mapped(path)), isdir=lambda path: os.path.isdir(mapped(path))),
                    makedirs=lambda path, **kwargs: os.makedirs(mapped(path), **kwargs),
                    utime=lambda *args: None,
                )
                engine.open = lambda path, *args, **kwargs: builtins.open(mapped(path), *args, **kwargs)
                engine.subprocess = SimpleNamespace(DEVNULL=subprocess.DEVNULL, check_output=lambda *args, **kwargs: "", run=lambda *args, **kwargs: None, Popen=lambda *args, **kwargs: None)
                engine.wl_env = lambda: {}
                with patch.dict(sys.modules, {"omarchy_session": SimpleNamespace(stop_session_processes=lambda *args: None)}):
                    self.assertTrue(engine.apply_theme("tokyo-night"))
                sway = (tmp_path / "root/.config/sway/config").read_text()
                self.assertIn(f"set $mod {primary}", sway)
                self.assertIn("bindsym $mod+d exec /usr/local/bin/omarchy-dock toggle", sway)
                self.assertEqual("bindsym $alt+d exec /usr/local/bin/omarchy-dock toggle" in sway, alt_mirror)
                css = (tmp_path / "root/.config/waybar/style.css").read_text()
                self.assertIn("margin-left: 30px;", css)
                self.assertIn("margin-right: 30px;", css)


if __name__ == "__main__":
    unittest.main()
