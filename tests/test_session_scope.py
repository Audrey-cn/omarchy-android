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
