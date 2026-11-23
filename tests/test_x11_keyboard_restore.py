"""Behavior checks for keyboard restoration; no compositor or device required."""

import copy
import json
import os
from pathlib import Path
import re
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "core/bin/omarchy-restore-x11-keyboard"
WRAPPER = ROOT / "core/bin/wtype"
HEADER = struct.Struct("<6sII")


def physical(identifier="0:0:x11-keyboard", delay=600, index=0):
    return {
        "identifier": identifier,
        "name": "x11-keyboard",
        "type": "keyboard",
        "repeat_delay": delay,
        "xkb_active_layout_index": index,
        "xkb_layout_names": ["English (US)", "German", "French"],
        "xkb_options": "grp:alt_shift_toggle,compose:ralt",
        "send_events": "disabled",
    }


class FakeSway:
    """Speak the real Unix IPC framing and model a configure resetting group."""

    def __init__(self, path, inputs, reject=None, ignore_layout=False, bad_header=False):
        self.path = str(path)
        self.inputs = copy.deepcopy(inputs)
        self.commands = []
        self.reject = reject
        self.ignore_layout = ignore_layout
        self.bad_header = bad_header
        self.error = None
        self.stopped = threading.Event()

    def __enter__(self):
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen()
        self.server.settimeout(0.1)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stopped.set()
        self.server.close()
        self.thread.join(timeout=2)
        if self.thread.is_alive():
            raise AssertionError("fake Sway did not stop")
        if self.error:
            raise self.error

    @staticmethod
    def read(connection, size):
        result = b""
        while len(result) < size:
            chunk = connection.recv(size - len(result))
            if not chunk:
                raise AssertionError("incomplete request")
            result += chunk
        return result

    def serve(self):
        try:
            while not self.stopped.is_set():
                try:
                    connection, _ = self.server.accept()
                except socket.timeout:
                    continue
                except OSError:
                    if self.stopped.is_set():
                        return
                    raise
                with connection:
                    magic, length, message_type = HEADER.unpack(self.read(connection, HEADER.size))
                    if magic != b"i3-ipc":
                        raise AssertionError("bad request magic")
                    payload = self.read(connection, length).decode()
                    if message_type == 100:
                        reply = self.inputs
                    elif message_type == 0:
                        self.commands.append(payload)
                        match = re.fullmatch(r'input "([0-9]+:[0-9]+:x11-keyboard)" (repeat_delay|xkb_switch_layout) ([0-9]+)', payload)
                        if not match:
                            raise AssertionError("unexpected command: " + payload)
                        identifier, operation, value = match.groups()
                        accepted = operation != self.reject
                        if accepted:
                            device = next(item for item in self.inputs if item.get("identifier") == identifier)
                            if operation == "repeat_delay":
                                device["repeat_delay"] = int(value)
                                device["xkb_active_layout_index"] = 0
                            elif not self.ignore_layout:
                                device["xkb_active_layout_index"] = int(value)
                        reply = [{"success": accepted}]
                    else:
                        raise AssertionError("unexpected message type")
                    encoded = json.dumps(reply).encode()
                    connection.sendall(HEADER.pack(b"bad-ip" if self.bad_header else b"i3-ipc", len(encoded), message_type) + encoded)
        except Exception as error:
            self.error = error


class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="omarchy-x11-test-")
        self.addCleanup(self.directory.cleanup)
        self.runtime = Path(self.directory.name)

    def run_helper(self, **settings):
        environment = os.environ.copy()
        environment.pop("SWAYSOCK", None)
        environment.pop("XDG_RUNTIME_DIR", None)
        environment.update(settings)
        return subprocess.run([sys.executable, str(HELPER)], env=environment, capture_output=True, text=True, timeout=10)

    def test_preserves_multiple_layouts_indices_delays_and_disabled_state(self):
        inputs = [physical(delay=375, index=2), physical("2:3:x11-keyboard", delay=910, index=1), {
            "name": "wlr_virtual_keyboard_v1", "type": "keyboard",
            "identifier": "0:0:wlr_virtual_keyboard_v1", "repeat_delay": 25,
        }]
        with FakeSway(self.runtime / "sway-ipc.test.sock", inputs) as sway:
            result = self.run_helper(SWAYSOCK=sway.path)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "")
            self.assertEqual(result.stderr, "")
            self.assertEqual(sway.inputs, inputs)
            self.assertEqual(sway.commands, [
                'input "0:0:x11-keyboard" repeat_delay 375',
                'input "0:0:x11-keyboard" xkb_switch_layout 2',
                'input "2:3:x11-keyboard" repeat_delay 910',
                'input "2:3:x11-keyboard" xkb_switch_layout 1',
            ])

    def test_no_sway_is_quiet_noop(self):
        result = self.run_helper(XDG_RUNTIME_DIR=str(self.runtime))
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))

    def test_no_physical_keyboard_is_quiet_noop(self):
        with FakeSway(self.runtime / "sway-ipc.test.sock", [{
            "name": "wlr_virtual_keyboard_v1", "type": "keyboard", "identifier": "0:0:x11-keyboard",
        }]) as sway:
            result = self.run_helper(SWAYSOCK=sway.path)
            self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))
            self.assertEqual(sway.commands, [])

    def test_stale_swaysock_falls_back_to_runtime_socket(self):
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stale.bind(str(self.runtime / "stale.sock"))
        stale.close()
        with FakeSway(self.runtime / "sway-ipc.good.sock", [physical()]) as sway:
            result = self.run_helper(SWAYSOCK=str(self.runtime / "stale.sock"), XDG_RUNTIME_DIR=str(self.runtime))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(len(sway.commands), 2)

    def test_existing_broken_ipc_fails(self):
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        path = self.runtime / "sway-ipc.stale.sock"
        stale.bind(str(path))
        stale.close()
        result = self.run_helper(SWAYSOCK=str(path))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("IPC connection failed", result.stderr)

    def test_bad_header_fails(self):
        with FakeSway(self.runtime / "sway-ipc.test.sock", [physical()], bad_header=True) as sway:
            result = self.run_helper(SWAYSOCK=sway.path)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, "")
            self.assertIn("invalid Sway IPC reply header", result.stderr)
            self.assertEqual(sway.commands, [])

    def test_missing_or_unsafe_current_metadata_never_changes_settings(self):
        for change in ({"repeat_delay": None}, {"repeat_delay": True},
                       {"xkb_active_layout_index": -1}, {"xkb_active_layout_index": 3},
                       {"identifier": "*"}):
            with self.subTest(change=change):
                device = physical()
                device.update(change)
                path = self.runtime / ("sway-ipc." + str(len(list(self.runtime.iterdir()))) + ".sock")
                with FakeSway(path, [device]) as sway:
                    result = self.run_helper(SWAYSOCK=sway.path)
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual(result.stdout, "")
                    self.assertEqual(sway.commands, [])

    def test_rejected_configuration_is_failure_and_still_restores_index(self):
        with FakeSway(self.runtime / "sway-ipc.test.sock", [physical(index=2)], reject="repeat_delay") as sway:
            result = self.run_helper(SWAYSOCK=sway.path)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, "")
            self.assertIn("rejected", result.stderr)
            self.assertEqual(sway.commands[-1], 'input "0:0:x11-keyboard" xkb_switch_layout 2')

    def test_success_ack_without_restored_layout_is_failure(self):
        with FakeSway(self.runtime / "sway-ipc.test.sock", [physical(index=2)], ignore_layout=True) as sway:
            result = self.run_helper(SWAYSOCK=sway.path)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, "")
            self.assertIn("was not restored", result.stderr)


class WrapperTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="omarchy-wtype-test-")
        self.addCleanup(self.directory.cleanup)
        self.runtime = Path(self.directory.name)
        self.marker = self.runtime / "restored"
        self.system = self.runtime / "system-wtype"
        self.helper = self.runtime / "restore-helper"

    def executable(self, path, body):
        path.write_text("#!" + sys.executable + "\n" + body)
        path.chmod(0o755)

    def run_wrapper(self, arguments=()):
        code = (
            "import runpy,sys; m=runpy.run_path(" + repr(str(WRAPPER)) + "); "
            "g=m['main'].__globals__; g['SYSTEM_WTYPE']=" + repr(str(self.system)) + "; "
            "g['RESTORE_HELPER']=" + repr(str(self.helper)) + "; "
            "sys.exit(m['exit_with_status'](m['main']()))"
        )
        return subprocess.run([sys.executable, "-c", code, *arguments], capture_output=True, text=True, timeout=10)

    def make_helper(self, status=0):
        self.executable(self.helper,
            "from pathlib import Path\nimport sys\n"
            "Path(" + repr(str(self.marker)) + ").write_text('yes')\n"
            "print('helper stdout must stay hidden')\n"
            "print('helper diagnostic', file=sys.stderr)\n"
            "sys.exit(" + str(status) + ")\n")

    def test_raw_arguments_streams_and_original_failure_status(self):
        arguments = ["-M", "ctrl", "-k", "Tab", "text with spaces", "$(touch forbidden);*"]
        self.executable(self.system, "import json,sys\nprint(json.dumps(sys.argv[1:]))\nprint('original stderr', file=sys.stderr)\nsys.exit(17)\n")
        self.make_helper(status=5)
        result = self.run_wrapper(arguments)
        self.assertEqual(result.returncode, 17)
        self.assertEqual(json.loads(result.stdout), arguments)
        self.assertIn("original stderr\n", result.stderr)
        self.assertIn("helper diagnostic\n", result.stderr)
        self.assertIn("restore failed (exit 5)", result.stderr)
        self.assertEqual(self.marker.read_text(), "yes")

    def test_helper_failure_does_not_change_success(self):
        self.executable(self.system, "pass\n")
        self.make_helper(status=7)
        result = self.run_wrapper()
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("restore failed (exit 7)", result.stderr)
        self.assertTrue(self.marker.exists())

    def test_original_signal_status_survives_cleanup(self):
        self.executable(self.system, "import os,signal\nos.kill(os.getpid(), signal.SIGTERM)\n")
        self.make_helper()
        result = self.run_wrapper()
        self.assertEqual(result.returncode, -signal.SIGTERM)
        self.assertEqual(result.stdout, "")
        self.assertTrue(self.marker.exists())

    def test_child_sigkill_status_survives_cleanup(self):
        self.executable(self.system, "import os,signal\nos.kill(os.getpid(), signal.SIGKILL)\n")
        self.make_helper()
        result = self.run_wrapper()
        self.assertEqual(result.returncode, -signal.SIGKILL)
        self.assertEqual(result.stdout, "")
        self.assertTrue(self.marker.exists())

    def test_missing_system_wtype_still_restores_and_returns_127(self):
        self.make_helper()
        result = self.run_wrapper()
        self.assertEqual(result.returncode, 127)
        self.assertEqual(result.stdout, "")
        self.assertIn("cannot run /usr/bin/wtype", result.stderr)
        self.assertTrue(self.marker.exists())


if __name__ == "__main__":
    unittest.main()
