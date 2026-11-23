import importlib.util
import os
import socket
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("omarchy_session", Path(__file__).resolve().parents[1] / "core/bin/omarchy_session.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SessionScope(unittest.TestCase):
    def test_inherited_session_wins_and_ambiguous_fallback_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            with socket.socket(socket.AF_UNIX) as first, socket.socket(socket.AF_UNIX) as second:
                a, b = str(folder / "sway-ipc.1.sock"), str(folder / "sway-ipc.2.sock")
                first.bind(a); first.listen(8)
                second.bind(b); second.listen(8)
                self.assertEqual(module.select_sway_socket({"SWAYSOCK": a}, folder), a)
                with self.assertRaisesRegex(RuntimeError, "explicit"):
                    module.select_sway_socket({}, folder)
                with self.assertRaisesRegex(RuntimeError, "no longer"):
                    module.select_sway_socket({"SWAYSOCK": str(folder / "missing")}, folder)

    def test_preserves_other_app_mount_namespace_and_compositor(self):
        with tempfile.TemporaryDirectory() as temp:
            proc = Path(temp)
            for pid, owner, namespace, socket in [(100, 10185, "1", "a"), (101, 10185, "1", "a"),
                                                  (102, 10186, "1", "a"), (103, 10185, "2", "a"),
                                                  (104, 10185, "1", "b")]:
                path = proc / str(pid)
                (path / "ns").mkdir(parents=True)
                (path / "status").write_text(f"Uid:\t{owner}\t{owner}\t{owner}\t{owner}\n")
                (path / "comm").write_text("waybar\n")
                (path / "environ").write_bytes(f"SWAYSOCK={socket}\0".encode())
                os.symlink("mnt:[" + namespace + "]", path / "ns/mnt")
            self.assertEqual(module.matching_processes({"waybar"}, {"SWAYSOCK": "a"}, proc, 100), [101])
            self.assertEqual(module.matching_processes({"waybar"}, {}, proc, 100), [])

    def test_mako_fallback_preserves_other_modes_and_reloads_without_busctl(self):
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        bus = Mock()
        modes = ["default", "presentation"]
        methods = []

        def call(dest, obj, interface, method, arguments, reply, flags, timeout, cancel):
            self.assertEqual((dest, obj, interface),
                             ("org.freedesktop.Notifications", "/fr/emersion/Mako", "fr.emersion.Mako"))
            methods.append(method)
            if method == "ListModes":
                return SimpleNamespace(unpack=lambda: (modes.copy(),))
            if method == "SetModes":
                signature, values = arguments
                self.assertEqual(signature, "(as)")
                modes[:] = values[0]

        bus.call_sync.side_effect = call
        gio = SimpleNamespace(BusType=SimpleNamespace(SESSION=1), DBusCallFlags=SimpleNamespace(NO_AUTO_START=2),
                              bus_get_sync=lambda *_: bus)
        gi_repository = SimpleNamespace(Gio=gio, GLib=SimpleNamespace(Variant=lambda t, v: (t, v)))
        with patch.dict("sys.modules", {"gi.repository": gi_repository}), patch.object(module.shutil, "which", return_value=None):
            module.mako_action("dnd-on")
            self.assertEqual(modes, ["default", "presentation", "do-not-disturb"])
            module.mako_action("dnd-on")
            module.mako_action("dnd-off")
            module.mako_action("reload")
            self.assertEqual(modes, ["default", "presentation"])
            self.assertEqual(methods, ["ListModes", "SetModes", "ListModes", "ListModes", "SetModes", "Reload"])
        with patch.object(module.shutil, "which", return_value="/bin/tool"), patch.object(module.subprocess, "run") as run:
            module.mako_action("dnd-on")
            self.assertEqual(run.call_args.args[0], ["makoctl", "mode", "-a", "do-not-disturb"])
