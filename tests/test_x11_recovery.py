"""Guest desktop recovery without X11, Wayland, GTK or a running compositor."""

import importlib.machinery
import importlib.util
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "core/bin/omarchy-recover-x11"


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(HELPER.is_file(), "missing guest Xserver restart recovery")
        loader = importlib.machinery.SourceFileLoader("x11_recovery", str(HELPER))
        spec = importlib.util.spec_from_loader("x11_recovery", loader)
        self.helper = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {}):
            sys.path.insert(0, str(ROOT / "core/bin"))
            try:
                loader.exec_module(self.helper)
            finally:
                sys.path.pop(0)
        self.env = {"DISPLAY": ":0", "XDG_RUNTIME_DIR": "/tmp"}
        listener = patch.object(self.helper, "unix_listening", return_value=True)
        listener.start()
        self.addCleanup(listener.stop)
        alias = patch.object(self.helper, "canonical_wayland", return_value="wayland-1", create=True)
        alias.start()
        self.addCleanup(alias.stop)
        self.session = ("/tmp/sway-ipc.0.101.sock", "wayland-2")
        self.processes = {
            101: ("sway", ["/usr/local/bin/sway"], self.env),
            102: ("fcitx5", ["fcitx5"], self.env),
            103: ("mako", ["mako"], self.env),
            104: ("python3", ["python3", "/usr/local/bin/omarchy-deck-bridge.py"], self.env),
            105: ("python3", ["python3", "/usr/local/bin/omarchy-audio-bridge.py"], self.env),
            106: ("dbus-daemon", ["dbus-daemon", "--session"], self.env),
        }

    def test_healthy_desktop_preserves_audio_bus_and_clients_without_spawning(self):
        with patch.object(self.helper, "session", return_value=self.session), \
             patch.object(self.helper, "scoped_processes", return_value=self.processes), \
             patch.object(self.helper, "deck_listening", return_value=True), \
             patch.object(self.helper, "spawn") as spawn, \
             patch.object(self.helper.subprocess, "run") as run, \
             patch.object(self.helper.os, "kill") as kill:
            self.helper.recover(self.env)
            spawn.assert_not_called()
            run.assert_not_called()
            kill.assert_not_called()

    def test_dead_sway_restarts_before_theme_and_preserves_healthy_deck(self):
        processes = {pid: value for pid, value in self.processes.items() if pid not in (101, 102, 103)}
        ready = False
        launches = []

        def spawn(argv, environment, log):
            nonlocal ready
            launches.append(argv)
            if Path(argv[0]).name == "sway":
                ready = True
            return Mock(poll=lambda: None)

        with patch.object(self.helper, "session", side_effect=lambda _: self.session if ready else None), \
             patch.object(self.helper, "scoped_processes", return_value=processes), \
             patch.object(self.helper, "deck_listening", return_value=True), \
             patch.object(self.helper, "spawn", side_effect=spawn), \
             patch.object(self.helper.subprocess, "run") as run:
            self.helper.recover(self.env)
            self.assertEqual(launches[0], ["/usr/local/bin/sway", "-c", "/root/.config/sway/config"])
            self.assertIn(["fcitx5", "-d", "--replace"], launches)
            self.assertIn(["mako"], launches)
            self.assertFalse(any("audio" in " ".join(args) or "dbus-daemon" in args or "deck-bridge" in " ".join(args) for args in launches))
            self.assertEqual(run.call_args.args[0], ["/usr/local/bin/omarchy-theme-engine.py", "restore"])
            self.assertEqual(run.call_args.kwargs["env"]["WAYLAND_DISPLAY"], "wayland-1")
            self.assertEqual(run.call_args.kwargs["env"]["SWAYSOCK"], self.session[0])

    def test_dead_sway_start_failure_never_reports_ready_or_launches_clients(self):
        with patch.object(self.helper, "session", return_value=None), \
             patch.object(self.helper, "scoped_processes", return_value={}), \
             patch.object(self.helper, "spawn", return_value=Mock(poll=lambda: 1)) as spawn, \
             patch.object(self.helper.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "Sway exited"):
                self.helper.recover(self.env)
            self.assertEqual(spawn.call_count, 1)
            run.assert_not_called()

    def test_scope_excludes_other_uid_mount_namespace_and_display(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            for pid, uid, namespace, display in [(100, 1001, "1", ":0"), (101, 1001, "1", ":0"),
                                                (102, 1002, "1", ":0"), (103, 1001, "2", ":0"),
                                                (104, 1001, "1", ":1")]:
                path = proc / str(pid)
                (path / "ns").mkdir(parents=True)
                (path / "status").write_text(f"Uid:\t{uid}\t{uid}\t{uid}\t{uid}\nState:\tS (sleeping)\n")
                (path / "comm").write_text("mako\n")
                (path / "cmdline").write_bytes(b"mako\0")
                (path / "environ").write_bytes(f"DISPLAY={display}\0XDG_RUNTIME_DIR=/tmp\0".encode())
                os.symlink("mnt:[" + namespace + "]", path / "ns/mnt")
            with patch.object(self.helper, "PROC", proc), patch.object(self.helper.os, "getpid", return_value=100):
                self.assertEqual(list(self.helper.scoped_processes(self.env)), [101])

    def test_wayland_discovery_uses_peer_pid_when_android_denies_proc_net(self):
        with tempfile.TemporaryDirectory() as tmp:
            runtime = Path(tmp)
            for name in ("wayland-1", "wayland-2", "wayland-2.lock"):
                (runtime / name).touch()
            with patch.object(Path, "read_text", side_effect=PermissionError("Android denies /proc/net")), \
                 patch.object(self.helper, "wayland_peer", side_effect=lambda path: 101 if path.name == "wayland-2" else 999):
                self.assertEqual(self.helper.owned_wayland(101, runtime), "wayland-2")

    def test_missing_bus_reports_failure_without_replacing_audio_or_bus(self):
        with patch.object(self.helper, "unix_listening", return_value=False), \
             patch.object(self.helper, "spawn") as spawn:
            with self.assertRaisesRegex(RuntimeError, "D-Bus is unavailable"):
                self.helper.recover(self.env)
            spawn.assert_not_called()


class WaylandAliasTests(unittest.TestCase):
    def test_existing_parent_shells_keep_working_through_canonical_socket_alias(self):
        namespace = {}
        # Import without RecoveryTests' mocked canonical socket publisher.
        self.assertTrue(HELPER.is_file())
        sys.path.insert(0, str(ROOT / "core/bin"))
        try:
            exec(compile(HELPER.read_text(), str(HELPER), "exec"), namespace)
        finally:
            sys.path.pop(0)
        self.assertIn("canonical_wayland", namespace)
        with tempfile.TemporaryDirectory() as tmp:
            runtime = Path(tmp)
            actual = runtime / "wayland-2"
            actual.touch()
            with patch.dict(namespace, {"unix_listening": lambda path: Path(path).resolve() == actual.resolve()}):
                self.assertEqual(namespace["canonical_wayland"](runtime, "wayland-2"), "wayland-1")
                self.assertEqual((runtime / "wayland-1").resolve(), actual.resolve())
                self.assertEqual(namespace["canonical_wayland"](runtime, "wayland-2"), "wayland-1")
            with patch.dict(namespace, {"unix_listening": lambda _: True}):
                with self.assertRaisesRegex(RuntimeError, "live.*Wayland"):
                    namespace["canonical_wayland"](runtime, "wayland-3")


if __name__ == "__main__":
    unittest.main()
