#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""PulseAudio <-> Android AAudio, using the tiny JNI F32LE socket protocol.

No Python packages are needed. --check exercises the wire/conversion helpers
without starting PulseAudio, creating files or opening sockets.
"""

import argparse
import errno
import fcntl
import json
import logging
import os
from pathlib import Path
import select
import shlex
import signal
import socket
import stat
import struct
import subprocess
import threading

RATE = 48000
AUDIO_SOCKET = "/tmp/.tiny.audio"
MIC_SOCKET = "/tmp/.tiny.mic"
MIC_FIFO = "/tmp/.tiny.mic.fifo"
PLAYBACK_HEADER = struct.pack("<III", RATE, 2, 0)
PAREC_COMMAND = [
    "parec", "--format=float32le", "--rate=48000", "--channels=2",
    "--device=omarchy_out.monitor", "--raw", "--latency-msec=25",
]
LOG = logging.getLogger("omarchy-audio")


def pulse_environment():
    return {**os.environ, "XDG_RUNTIME_DIR": "/tmp",
            "PULSE_RUNTIME_PATH": "/tmp/pulse", "PULSE_SERVER": "unix:/tmp/pulse/native"}


def configure_pulse(fifo_path=MIC_FIFO):
    """Reuse matching modules; replace only this bridge's stale configuration."""
    env = pulse_environment()

    def run(*args, check=True):
        return subprocess.run(list(args), env=env, check=check, capture_output=True,
                              text=True, timeout=10)

    if run("pactl", "info", check=False).returncode:
        run("pulseaudio", "--daemonize=yes", "--exit-idle-time=-1", "--disallow-exit",
            "--disable-shm=yes", "--fail=no")
        run("pactl", "info")

    modules = []
    for line in run("pactl", "list", "short", "modules").stdout.splitlines():
        fields = line.split("\t")
        if len(fields) >= 3:
            modules.append((fields[0], fields[1], shlex.split(fields[2])))

    def ensure(name, arguments, identity, fifo=None):
        matches = [(index, args) for index, module, args in modules
                   if module == name and identity in args]
        keep = None
        for index, args in matches:
            fifo_ok = fifo is None or (fifo.exists() and stat.S_ISFIFO(fifo.lstat().st_mode))
            if keep is None and set(arguments).issubset(args) and fifo_ok:
                keep = index
            else:
                run("pactl", "unload-module", index)
        if keep is None:
            if fifo is not None:
                # module-pipe-source calls mkfifo itself, failing on EEXIST.
                # Preserve active modules above; remove only a stale FIFO here.
                try:
                    if not stat.S_ISFIFO(fifo.lstat().st_mode):
                        raise ValueError("Microphone FIFO path is not a FIFO: " + str(fifo))
                    fifo.unlink()
                except FileNotFoundError:
                    pass
            run("pactl", "load-module", name, *arguments)

    ensure("module-null-sink", [
        "sink_name=omarchy_out", "rate=48000", "channels=2", "format=float32le",
        "sink_properties=device.description=Omarchy_Audio",
    ], "sink_name=omarchy_out")
    fifo = Path(fifo_path)
    ensure("module-pipe-source", [
        "source_name=omarchy_mic", "file=" + str(fifo), "rate=48000", "channels=1",
        "format=float32le", "source_properties=device.description=Omarchy_Mic",
    ], "source_name=omarchy_mic", fifo)
    if not stat.S_ISFIFO(fifo.lstat().st_mode):
        raise ValueError("PulseAudio did not create the microphone FIFO")
    run("pactl", "set-default-sink", "omarchy_out")
    run("pactl", "set-default-source", "omarchy_mic")


def read_exact(read, size):
    result = bytearray()
    while len(result) < size:
        chunk = read(size - len(result))
        if not chunk:
            raise EOFError("Incomplete tiny audio header/stream")
        result.extend(chunk)
    return bytes(result)


def forward_playback(chunks, send):
    """send must consume the full buffer (e.g. socket.sendall)."""
    send(PLAYBACK_HEADER)
    for chunk in chunks:
        if chunk:
            send(chunk)


def parse_microphone_header(header):
    if len(header) != 12:
        raise ValueError("Microphone header must be 12 bytes")
    rate, channels, fmt = struct.unpack("<III", header)
    if not 1 <= rate <= 384000 or channels not in (1, 2) or fmt != 0:
        raise ValueError("Unsupported microphone rate/channels/format: %r" % ((rate, channels, fmt),))
    return rate, channels, fmt


class MicrophonePCM:
    """Keep fragmented frames intact; downmix and linearly resample to 48k mono.

    Integer phase keeps resampling continuous across arbitrary recv boundaries.
    The common 48k/mono case preserves the incoming float bits exactly.
    """

    def __init__(self, rate, channels):
        parse_microphone_header(struct.pack("<III", rate, channels, 0))
        self.rate = rate
        self.channels = channels
        self.pending = b""
        self.previous = None
        self.index = 0
        self.phase = 0

    def feed(self, chunk):
        data = self.pending + chunk
        boundary = len(data) // (4 * self.channels) * (4 * self.channels)
        data, self.pending = data[:boundary], data[boundary:]
        if not data:
            return b""
        if self.channels == 2:
            data = b"".join(struct.pack("<f", (left + right) * 0.5)
                            for left, right in struct.iter_unpack("<ff", data))
        if self.rate == RATE:
            return data
        output = bytearray()
        for (sample,) in struct.iter_unpack("<f", data):
            if self.previous is None:
                output.extend(struct.pack("<f", sample))
                self.phase = self.rate
            else:
                end = self.index * RATE
                while self.phase <= end:
                    fraction = (self.phase - (end - RATE)) / RATE
                    output.extend(struct.pack("<f", self.previous + (sample - self.previous) * fraction))
                    self.phase += self.rate
            self.previous = sample
            self.index += 1
        return bytes(output)


def forward_microphone(read, write):
    rate, channels, _ = parse_microphone_header(read_exact(read, 12))
    converter = MicrophonePCM(rate, channels)
    while True:
        chunk = read(8192)
        if not chunk:
            return
        pcm = converter.feed(chunk)
        if pcm:
            write(pcm)


class FifoWriter:
    """Never block capture when no Linux app is recording.

    Writes <= PIPE_BUF are atomic on a nonblocking FIFO, so an overflow drops
    complete float32 frames rather than leaving a partial sample behind.
    Reopen after a missing reader/broken pipe; do not keep an artificial reader.
    """

    def __init__(self, path=MIC_FIFO):
        self.path = path
        self.fd = None

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def write(self, data):
        if len(data) % 4:
            raise ValueError("FIFO requires complete float32 frames")
        try:
            if self.fd is None:
                self.fd = os.open(self.path, os.O_WRONLY | os.O_NONBLOCK)
            limit = os.fpathconf(self.fd, "PC_PIPE_BUF") // 4 * 4
            sent = 0
            while sent < len(data):
                chunk = data[sent:sent + limit]
                try:
                    count = os.write(self.fd, chunk)
                except InterruptedError:
                    continue
                except BlockingIOError:
                    return sent
                if count != len(chunk):
                    raise OSError(errno.EIO, "Non-atomic FIFO write")
                sent += count
            return sent
        except OSError as error:
            self.close()
            if error.errno not in (errno.ENXIO, errno.ENOENT, errno.EPIPE):
                raise
            return 0


def playback_client(client, stop):
    process = None

    def chunks():
        while not stop.is_set():
            ready, _, _ = select.select([process.stdout, client], [], [], 0.5)
            if client in ready:
                # Playback is server -> client only. EOF (or unexpected input)
                # releases the capture even when parec has no data to read.
                return
            if process.stdout in ready:
                chunk = os.read(process.stdout.fileno(), 9600)
                if not chunk:
                    return
                yield chunk

    try:
        process = subprocess.Popen(PAREC_COMMAND, stdout=subprocess.PIPE,
                                   stdin=subprocess.DEVNULL, env=pulse_environment(), bufsize=0)
        forward_playback(chunks(), client.sendall)
    finally:
        if process is not None:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()


def microphone_client(client, stop):
    def read(size):
        while not stop.is_set():
            try:
                return client.recv(size)
            except socket.timeout:
                continue
        return b""

    writer = FifoWriter()
    try:
        forward_microphone(read, writer.write)
    finally:
        writer.close()


def serve(path, handler, stop):
    path = Path(path)
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError:
        pass
    else:
        if not stat.S_ISSOCK(mode):
            raise ValueError("Refusing to replace non-socket: " + str(path))
        path.unlink()
    identity = None
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
        try:
            server.bind(str(path))
            identity = path.lstat()
            server.listen(2)
            server.settimeout(0.5)
            LOG.info("Listening on %s", path)
            while not stop.is_set():
                try:
                    client, _ = server.accept()
                except socket.timeout:
                    continue
                with client:
                    client.settimeout(1)
                    try:
                        handler(client, stop)
                    except (OSError, EOFError, ValueError) as error:
                        LOG.info("%s client ended: %s", path.name, error)
        finally:
            try:
                current = path.lstat()
                if identity is not None and (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    path.unlink()
            except FileNotFoundError:
                pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="self-check only; no PulseAudio/files/sockets")
    args = parser.parse_args(argv)
    if args.check:
        assert PLAYBACK_HEADER == bytes.fromhex("80bb00000200000000000000")
        assert parse_microphone_header(struct.pack("<III", RATE, 1, 0)) == (RATE, 1, 0)
        assert MicrophonePCM(RATE, 2).feed(struct.pack("<ff", 1, -1)) == b"\0" * 4
        print(json.dumps({"ok": True, "dry_run": True, "playback_header_hex": PLAYBACK_HEADER.hex(),
                          "audio_socket": AUDIO_SOCKET, "mic_socket": MIC_SOCKET, "mic_fifo": MIC_FIFO}))
        return 0

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    stop = threading.Event()
    errors = []

    def worker(path, handler):
        try:
            serve(path, handler, stop)
        except Exception:
            LOG.exception("Audio server failed: %s", path)
            errors.append(path)
            stop.set()

    def shutdown(signum, frame):
        stop.set()

    # Serialize startup too: a second invocation must never unlink live sockets.
    with open("/tmp/.omarchy-audio-bridge.lock", "a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            LOG.info("Audio bridge is already running")
            return 0
        try:
            signal.signal(signal.SIGTERM, shutdown)
            signal.signal(signal.SIGINT, shutdown)
            Path("/tmp/pulse").mkdir(mode=0o700, exist_ok=True)
            configure_pulse()
            threads = [threading.Thread(target=worker, args=(path, handler), name=name)
                       for path, handler, name in ((AUDIO_SOCKET, playback_client, "playback"),
                                                   (MIC_SOCKET, microphone_client, "microphone"))]
            for thread in threads:
                thread.start()
            try:
                stop.wait()
            finally:
                stop.set()
                for thread in threads:
                    thread.join()
        except (OSError, ValueError, subprocess.SubprocessError):
            LOG.exception("Could not initialize audio bridge")
            return 1
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
