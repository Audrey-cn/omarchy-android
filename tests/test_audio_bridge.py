"""JNI wire contracts and stream/FIFO behavior without requiring PulseAudio."""

import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "core/bin/omarchy-audio-bridge.py"
HEADER = b"\x80\xbb\x00\x00\x02\x00\x00\x00\x00\x00\x00\x00"


def load_bridge():
    assert SCRIPT.is_file(), "Linux audio bridge is missing"
    spec = importlib.util.spec_from_file_location("audio_bridge", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AudioStreams(unittest.TestCase):
    def setUp(self):
        self.bridge = load_bridge()

    def test_playback_header_and_pcm_on_fragmented_stream(self):
        pcm = struct.pack("<4f", 0.25, -0.25, 0.5, -0.5)
        output = io.BytesIO()
        self.bridge.forward_playback([pcm[:3], pcm[3:9], pcm[9:]], output.write)
        self.assertEqual(output.getvalue(), HEADER + pcm)
        self.assertEqual(len(HEADER), 12)

    def test_playback_reconnect_starts_with_fresh_header(self):
        for _ in range(2):
            output = io.BytesIO()
            self.bridge.forward_playback([b"\x00" * 8], output.write)
            self.assertEqual(output.getvalue(), HEADER + b"\x00" * 8)

    def test_microphone_header_and_fragmented_mono_pcm_forwarding(self):
        pcm = struct.pack("<3f", 0.25, -0.5, 1.0)
        source = io.BytesIO(struct.pack("<III", 48000, 1, 0) + pcm)
        output = io.BytesIO()
        self.bridge.forward_microphone(lambda size: source.read(min(size, 3)), output.write)
        self.assertEqual(output.getvalue(), pcm)

    def test_microphone_stereo_is_downmixed_across_frame_boundaries(self):
        source = io.BytesIO(struct.pack("<III4f", 48000, 2, 0, 1.0, -0.5, -1.0, 0.5))
        output = io.BytesIO()
        self.bridge.forward_microphone(lambda size: source.read(min(size, 5)), output.write)
        self.assertEqual(output.getvalue(), struct.pack("<2f", 0.25, -0.25))

    def test_microphone_resampling_keeps_phase_between_chunks(self):
        converter = self.bridge.MicrophonePCM(24000, 1)
        result = b"".join(converter.feed(struct.pack("<f", n)) for n in (0, 1, 0))
        self.assertEqual(struct.unpack("<5f", result), (0, 0.5, 1, 0.5, 0))
        converter = self.bridge.MicrophonePCM(96000, 1)
        result = converter.feed(struct.pack("<5f", 0, 1, 2, 3, 4))
        self.assertEqual(struct.unpack("<3f", result), (0, 2, 4))

    def test_microphone_preserves_mono_bits_and_drops_only_incomplete_tail(self):
        pcm = b"\x01\x00\xc0\x7f\x00\x00\x00\x80"  # NaN payload, negative zero
        output = io.BytesIO()
        self.bridge.forward_microphone(io.BytesIO(struct.pack("<III", 48000, 1, 0) + pcm + b"x").read, output.write)
        self.assertEqual(output.getvalue(), pcm)

    def test_microphone_rejects_truncated_or_unsupported_header(self):
        for header in (b"", HEADER[:11], struct.pack("<III", 0, 1, 0),
                       struct.pack("<III", 48000, 3, 0), struct.pack("<III", 48000, 1, 1)):
            with self.subTest(header=header), self.assertRaises((ValueError, EOFError)):
                self.bridge.forward_microphone(io.BytesIO(header).read, lambda data: self.fail("invalid PCM forwarded"))
        self.assertEqual(self.bridge.parse_microphone_header(struct.pack("<III", 44100, 2, 0)), (44100, 2, 0))

    def test_nonblocking_fifo_delivers_pcm_and_recovers_after_reader_closes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mic.fifo"
            os.mkfifo(path)
            writer = self.bridge.FifoWriter(path)
            self.addCleanup(writer.close)
            self.assertEqual(writer.write(b"\x00" * 4), 0)  # no reader: must not block
            reader = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            try:
                pcm = struct.pack("<2f", 0.5, -0.25)
                self.assertEqual(writer.write(pcm), len(pcm))
                self.assertEqual(os.read(reader, 100), pcm)
            finally:
                os.close(reader)
            self.assertEqual(writer.write(pcm), 0)  # EPIPE: reset descriptor
            reader = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            try:
                self.assertEqual(writer.write(pcm), len(pcm))
                self.assertEqual(os.read(reader, 100), pcm)
            finally:
                os.close(reader)

    def test_full_fifo_drops_whole_frames_without_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mic.fifo"
            os.mkfifo(path)
            reader = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            writer = self.bridge.FifoWriter(path)
            try:
                pcm = struct.pack("<f", 0.5) * 1024
                for _ in range(1024):
                    if writer.write(pcm) == 0:
                        break
                else:
                    self.fail("FIFO never filled")
                buffered = os.read(reader, 1024 * 1024)
                self.assertEqual(len(buffered) % 4, 0)
                self.assertEqual(buffered, struct.pack("<f", 0.5) * (len(buffered) // 4))
                self.assertEqual(writer.write(pcm), len(pcm))
            finally:
                writer.close()
                os.close(reader)

    def test_check_is_quick_and_does_not_start_daemons_or_bind_sockets(self):
        with patch.object(self.bridge.subprocess, "run", side_effect=AssertionError("spawn")), \
             patch.object(self.bridge.socket, "socket", side_effect=AssertionError("socket")), \
             patch("builtins.print") as printer:
            self.assertEqual(self.bridge.main(["--check"]), 0)
        self.assertEqual(json.loads(printer.call_args.args[0])["playback_header_hex"], HEADER.hex())
        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(os.access(SCRIPT, os.X_OK))

    def test_playback_idle_disconnect_reaps_child_without_real_sockets(self):
        children = []
        native_popen = subprocess.Popen
        output = io.BytesIO()

        class Client:
            sendall = output.write

        client = Client()

        def capture(*args, **kwargs):
            child = native_popen(*args, **kwargs)
            children.append(child)
            return child

        def ready_to_disconnect(readers, writers, errors, timeout):
            return [client], [], []

        command = [sys.executable, "-c", "import time;time.sleep(30)"]
        with patch.object(self.bridge, "PAREC_COMMAND", command), \
             patch.object(self.bridge.subprocess, "Popen", side_effect=capture), \
             patch.object(self.bridge.select, "select", side_effect=ready_to_disconnect):
            self.bridge.playback_client(client, threading.Event())
        self.assertEqual(output.getvalue(), HEADER)
        self.assertIsNotNone(children[0].poll())
        self.assertTrue(children[0].stdout.closed)

    def test_microphone_client_handles_timeout_eof_and_reconnect_into_real_fifo(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mic.fifo"
            os.mkfifo(path)
            reader = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            native_writer = self.bridge.FifoWriter
            stop = threading.Event()
            header = struct.pack("<III", 48000, 1, 0)

            class Client:
                def __init__(self, pcm):
                    self.chunks = iter([socket.timeout(), header[:3], header[3:], pcm, b"x", b""])

                def recv(self, size):
                    chunk = next(self.chunks)
                    if isinstance(chunk, Exception):
                        raise chunk
                    return chunk

            try:
                with patch.object(self.bridge, "FifoWriter", side_effect=lambda: native_writer(path)):
                    for value in (0.25, -0.5):
                        pcm = struct.pack("<f", value)
                        self.bridge.microphone_client(Client(pcm), stop)
                        self.assertEqual(os.read(reader, 100), pcm)
            finally:
                os.close(reader)

    def test_microphone_stop_interrupts_receive_timeout(self):
        stop = threading.Event()
        header = struct.pack("<III", 48000, 1, 0)

        class Client:
            first = True

            def recv(self, size):
                if self.first:
                    self.first = False
                    return header
                stop.set()
                raise socket.timeout()

        self.bridge.microphone_client(Client(), stop)


class PulseSetup(unittest.TestCase):
    def setUp(self):
        self.bridge = load_bridge()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.fifo = Path(self.directory.name) / "mic.fifo"
        self.modules = {}
        self.calls = []
        self.running = False

    def pulse_command(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        status, output = 0, ""
        if argv[0] == "pulseaudio":
            self.running = True
        elif argv[1:] == ["info"]:
            status = 0 if self.running else 1
        elif argv[1:] == ["list", "short", "modules"]:
            output = "".join(f"{i}\t{name}\t{' '.join(arguments)}\t0\n" for i, (name, arguments) in self.modules.items())
        elif argv[1] == "load-module":
            name, arguments = argv[2], argv[3:]
            if name == "module-pipe-source":
                # PA creates its FIFO itself and fails if it already exists.
                os.mkfifo(self.fifo)
            index = str(max([9] + [int(index) for index in self.modules]) + 1)
            self.modules[index] = (name, arguments)
            output = index + "\n"
        elif argv[1] == "unload-module":
            name, _ = self.modules.pop(argv[2])
            if name == "module-pipe-source":
                self.fifo.unlink(missing_ok=True)
        if kwargs.get("check") and status:
            raise subprocess.CalledProcessError(status, argv)
        return subprocess.CompletedProcess(argv, status, output, "")

    def test_user_daemon_arguments_modules_defaults_and_idempotence(self):
        os.mkfifo(self.fifo)  # stale FIFO must be removed before module load
        with patch.object(self.bridge.subprocess, "run", side_effect=self.pulse_command):
            self.bridge.configure_pulse(fifo_path=self.fifo)
            first_modules = dict(self.modules)
            self.bridge.configure_pulse(fifo_path=self.fifo)
        self.assertEqual(self.modules, first_modules)
        self.assertEqual(len(self.modules), 2)
        commands = [argv for argv, _ in self.calls]
        self.assertIn([
            "pulseaudio", "--daemonize=yes", "--exit-idle-time=-1", "--disallow-exit",
            "--disable-shm=yes", "--fail=no", "-n",
            "-L", "module-native-protocol-unix auth-anonymous=1 socket=/tmp/pulse/native",
        ], commands)
        self.assertEqual(sum(argv[0] == "pulseaudio" for argv in commands), 1)
        self.assertIn(["pactl", "load-module", "module-null-sink", "sink_name=omarchy_out", "rate=48000", "channels=2", "format=float32le", "sink_properties=device.description=Omarchy_Audio"], commands)
        self.assertIn(["pactl", "load-module", "module-pipe-source", "source_name=omarchy_mic", "file=" + str(self.fifo), "rate=48000", "channels=1", "format=float32le", "source_properties=device.description=Omarchy_Mic"], commands)
        self.assertIn(["pactl", "set-default-sink", "omarchy_out"], commands)
        self.assertIn(["pactl", "set-default-source", "omarchy_mic"], commands)
        for _, kwargs in self.calls:
            self.assertEqual(kwargs["env"]["XDG_RUNTIME_DIR"], "/tmp")
            self.assertEqual(kwargs["env"]["PULSE_RUNTIME_PATH"], "/tmp/pulse")
            self.assertEqual(kwargs["env"]["PULSE_SERVER"], "unix:/tmp/pulse/native")

    def test_existing_wrong_module_is_replaced_without_touching_unrelated_module(self):
        self.running = True
        self.modules = {"1": ("module-null-sink", ["sink_name=unrelated"]),
                        "2": ("module-null-sink", ["sink_name=omarchy_out", "rate=44100"])}
        with patch.object(self.bridge.subprocess, "run", side_effect=self.pulse_command):
            self.bridge.configure_pulse(fifo_path=self.fifo)
        commands = [argv for argv, _ in self.calls]
        self.assertIn(["pactl", "unload-module", "2"], commands)
        self.assertNotIn(["pactl", "unload-module", "1"], commands)
        self.assertFalse(any(argv[0] == "pulseaudio" for argv in commands))

    def test_wrong_microphone_module_recreates_fifo_and_subsequently_reuses_it(self):
        self.running = True
        os.mkfifo(self.fifo)
        self.modules["2"] = ("module-pipe-source", ["source_name=omarchy_mic", "rate=44100", "channels=2"])
        with patch.object(self.bridge.subprocess, "run", side_effect=self.pulse_command):
            self.bridge.configure_pulse(fifo_path=self.fifo)
            replacement = dict(self.modules)
            self.bridge.configure_pulse(fifo_path=self.fifo)
        commands = [argv for argv, _ in self.calls]
        self.assertIn(["pactl", "unload-module", "2"], commands)
        self.assertEqual(self.modules, replacement)
        self.assertEqual(sum(argv[1:3] == ["load-module", "module-pipe-source"] for argv in commands), 1)


class AudioImage(unittest.TestCase):
    def test_container_installs_pulse_and_launches_canonical_audio_bridge(self):
        workflow = (REPO / ".github/workflows/build-container.yml").read_text()
        template = (REPO / "apps/android/container/omarchy.tiny.yaml").read_text()
        self.assertRegex(workflow, r"\bpulseaudio\s+pulseaudio-utils\b")
        self.assertIn('sudo cp apps/android/container/omarchy.tiny.yaml "$ROOTFS/.tiny.yaml"', workflow)
        self.assertIn("setsid python3 /usr/local/bin/omarchy-audio-bridge.py >/tmp/audio-bridge.log 2>&1 &", template)
        self.assertIn("export PULSE_SERVER=unix:/tmp/pulse/native", template)
        self.assertIn("export XDG_RUNTIME_DIR=/tmp", template)


class AudioReconnect(unittest.TestCase):
    def test_playback_accepts_reconnect_and_reaps_capture_even_when_stdout_is_idle(self):
        bridge = load_bridge()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.sock"
            probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                probe.bind(str(path))
            except PermissionError:
                self.skipTest("Unix sockets restricted; pure Python wire/FIFO tests still run")
            finally:
                probe.close()
            path.unlink()  # serve also handles stale socket files
            stop = threading.Event()
            children = []
            native_popen = subprocess.Popen

            def capture(*args, **kwargs):
                child = native_popen(*args, **kwargs)
                children.append(child)
                return child

            command = [sys.executable, "-c", "import sys,time;sys.stdout.buffer.write(b'12345678');sys.stdout.buffer.flush();time.sleep(30)"]
            with patch.object(bridge, "PAREC_COMMAND", command), patch.object(bridge.subprocess, "Popen", side_effect=capture):
                thread = threading.Thread(target=bridge.serve, args=(path, bridge.playback_client, stop))
                thread.start()
                try:
                    for _ in range(2):
                        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                            client.settimeout(2)
                            deadline = time.monotonic() + 2
                            while True:
                                try:
                                    client.connect(str(path))
                                    break
                                except (FileNotFoundError, ConnectionRefusedError):
                                    if time.monotonic() > deadline:
                                        raise
                                    time.sleep(0.01)
                            self.assertEqual(bridge.read_exact(client.recv, 20), HEADER + b"12345678")
                    deadline = time.monotonic() + 2
                    while any(child.poll() is None for child in children) and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertEqual(len(children), 2)
                    self.assertTrue(all(child.poll() is not None for child in children))
                finally:
                    stop.set()
                    thread.join(3)
                    for child in children:
                        if child.poll() is None:
                            child.kill()
                            child.wait()
                self.assertFalse(thread.is_alive())
                self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
