"""Dock process ownership, Sway integration, and phone-safe bar defaults."""

import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import time
from unittest.mock import patch

import pytest

ROOT = Path(__file__).resolve().parents[1]


def load(name, relative):
    path = ROOT / relative
    assert path.is_file(), f"missing desktop command: {relative}"
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def test_bar_inset_defaults_to_30_and_preserves_saved_values_with_64px_cap(tmp_path):
    engine = load("dock_theme", "core/bin/omarchy-theme-engine.py")
    path = tmp_path / "bar-inset"
    assert engine.load_bar_inset(path) == 30
    for saved, expected in [("32", 32), ("56", 56), ("100", 64), ("-1", 0), ("bad", 30)]:
        path.write_text(saved)
        assert engine.load_bar_inset(path) == expected


def test_dock_collects_tiled_and_floating_sway_windows_without_containers(tmp_path):
    dock = load("dock_windows", "core/bin/omarchy-dock")
    tree = {"nodes": [{"id": 1, "type": "workspace", "name": "1", "nodes": [
        {"id": 42, "type": "con", "app_id": "Alacritty", "name": "Terminal", "focused": True},
        {"id": 43, "type": "con", "name": "empty split", "nodes": []},
    ]}], "floating_nodes": [{"id": 99, "type": "con", "name": "Files", "window_properties": {"class": "Thunar"}}]}
    swaymsg = tmp_path / "swaymsg"
    # Exercise the real subprocess boundary without starting another Python
    # interpreter inside the dock's short production IPC timeout.
    swaymsg.write_text(
        '#!/bin/sh\n[ "$1" = "-t" ] && [ "$2" = "get_tree" ] || exit 2\n'
        "printf '%s\\n' " + shlex.quote(json.dumps(tree)) + "\n"
    )
    swaymsg.chmod(0o755)
    trace = []
    check_output = subprocess.check_output

    def traced_check_output(*args, **kwargs):
        assert kwargs["timeout"] == 1
        try:
            # Tree parsing is the subject of this test. Give the host fixture
            # scheduling headroom while preserving the production IPC budget.
            result = check_output(*args, **{**kwargs, "timeout": 5})
        except Exception as error:
            trace.append((type(error).__name__, str(error)))
            raise
        trace.append(("output", result))
        return result

    with patch.dict(os.environ, {"PATH": str(tmp_path)}):
        with patch.object(dock.subprocess, "check_output", side_effect=traced_check_output):
            windows = dock.sway_windows(dict(os.environ))
    assert [(window["id"], window["name"]) for window in windows] == [(42, "Terminal"), (99, "Files")], trace
    assert windows[0]["focused"]
    assert dock.sway_windows({"PATH": str(tmp_path / "missing")}) == []


@pytest.mark.parametrize("response", [subprocess.TimeoutExpired(["swaymsg", "-t", "get_tree"], 1), "{invalid json"])
def test_dock_unavailable_or_malformed_sway_ipc_returns_empty_list(response):
    dock = load("dock_failed_ipc", "core/bin/omarchy-dock")
    boundary = {"side_effect": response} if isinstance(response, Exception) else {"return_value": response}
    with patch.object(dock.subprocess, "check_output", **boundary) as ipc:
        assert dock.sway_windows({"SWAYSOCK": "fixture-sway"}) == []
    assert ipc.call_args.kwargs["timeout"] == 1


def test_dock_toggle_starts_then_closes_and_reclaims_stale_pid(tmp_path):
    dock_path = ROOT / "core/bin/omarchy-dock"
    assert dock_path.is_file(), "missing dock implementation"
    pidfile = tmp_path / "omarchy-dock.pid"
    ready = tmp_path / "ready"
    # A GTK boundary substitute lets the real flock/PID/signal lifecycle run headlessly.
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

    # An unlocked stale PID must never kill the unrelated live process it names.
    pidfile.write_text(str(os.getpid()))
    first = invoke()
    try:
        deadline = time.monotonic() + 5
        while not ready.exists() and first.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        assert ready.exists(), first.communicate(timeout=1)
        assert pidfile.read_text().strip() == str(first.pid)
        lockfile = Path(str(pidfile) + ".lock")
        lifetime_inode = lockfile.stat().st_ino
        second = invoke()
        _, error = second.communicate(timeout=5)
        assert second.returncode == 0, error
        _, error = first.communicate(timeout=5)
        assert first.returncode == 0, error
        assert not pidfile.exists()
        assert lockfile.stat().st_ino == lifetime_inode
    finally:
        if first.poll() is None:
            first.terminate()
            first.communicate(timeout=5)


def test_dock_pins_core_apps_and_is_executable():
    dock = load("dock_pins", "core/bin/omarchy-dock")
    assert [entry[0] for entry in dock.PINNED_APPS] == ["Apps Grid", "Terminal", "Browser", "Files", "Settings / Menu"]
    assert os.access(ROOT / "core/bin/omarchy-dock", os.X_OK)


def test_concurrent_toggle_never_signals_a_stale_unrelated_pid(tmp_path):
    dock_path = ROOT / "core/bin/omarchy-dock"
    pidfile = tmp_path / "dock.pid"
    paused, release = tmp_path / "paused", tmp_path / "release"
    unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(20)"])
    pidfile.write_text(str(unrelated.pid))
    script = """import importlib.machinery, importlib.util, signal, sys, time
from pathlib import Path
loader = importlib.machinery.SourceFileLoader('dock', sys.argv[1])
spec = importlib.util.spec_from_loader('dock', loader)
dock = importlib.util.module_from_spec(spec)
loader.exec_module(dock)
def close(*_):
    raise SystemExit(0)
signal.signal(signal.SIGTERM, close)
if len(sys.argv) > 3:
    real_flock = dock.fcntl.flock
    def delayed_flock(fd, flags):
        result = real_flock(fd, flags)
        if flags & dock.fcntl.LOCK_NB:
            Path(sys.argv[3]).touch()
            deadline = time.monotonic() + 5
            while not Path(sys.argv[4]).exists() and time.monotonic() < deadline:
                time.sleep(0.01)
        return result
    dock.fcntl.flock = delayed_flock
def ui():
    while True:
        time.sleep(0.01)
dock.run_dock = ui
raise SystemExit(dock.toggle(sys.argv[2]))
"""
    first = subprocess.Popen([sys.executable, "-c", script, str(dock_path), str(pidfile), str(paused), str(release)])
    second = None
    try:
        deadline = time.monotonic() + 5
        while not paused.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        assert paused.exists()
        second = subprocess.Popen([sys.executable, "-c", script, str(dock_path), str(pidfile)])
        time.sleep(0.15)
        assert unrelated.poll() is None, "a concurrent toggle signalled a stale unrelated PID"
        release.touch()
        assert second.wait(timeout=5) == 0
        assert first.wait(timeout=5) == 0
        assert not pidfile.exists()
    finally:
        release.touch()
        for process in (first, second, unrelated):
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=5)


@pytest.mark.parametrize("mode,primary,alt_mirror", [("super", "Mod4", False), ("alt", "Mod1", False), ("super+alt", "Mod4", True)])
def test_generated_sway_dock_bindings_follow_modifier_mode(tmp_path, mode, primary, alt_mirror):
    import builtins
    from types import SimpleNamespace

    engine = load("dock_theme_generation", "core/bin/omarchy-theme-engine.py")
    def mapped(path):
        value = str(path)
        if value.startswith("/root/") or value == "/tmp/omarchy-sway.conf":
            return tmp_path / value.lstrip("/")
        return path

    theme = tmp_path / "themes/tokyo-night"
    theme.mkdir(parents=True)
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
        assert engine.apply_theme("tokyo-night")
    sway = (tmp_path / "root/.config/sway/config").read_text()
    assert f"set $mod {primary}" in sway
    assert "bindsym $mod+d exec /usr/local/bin/omarchy-dock toggle" in sway
    assert ("bindsym $alt+d exec /usr/local/bin/omarchy-dock toggle" in sway) is alt_mirror
    css = (tmp_path / "root/.config/waybar/style.css").read_text()
    assert "margin-left: 30px;" in css
    assert "margin-right: 30px;" in css
