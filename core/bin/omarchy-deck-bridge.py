#!/usr/bin/env python3
"""
Omarchy Deck Bridge (Linux-side receiver for OmarchyDeck.apk)
- TCP 127.0.0.1:18900 -> Receives 16kHz 1ch s16le PCM from Android AudioRecord
                         and writes to PulseAudio module-pipe-source FIFO!
- TCP 127.0.0.1:18901 -> Receives JSON keystrokes / WM commands from native
                         Android Virtual Keyboard & Floating Pill!
Works in BOTH Root (chroot) and Non-Root (proot) modes.
"""
import os
import glob
import json
import time
import socket
import threading
import subprocess

MIC_FIFO = "/tmp/omarchy-mic.pcm"
TERMUX_MIC_FIFO = "/data/data/com.termux/files/usr/tmp/omarchy-mic.pcm"
MIC_PORT = 18900
CMD_PORT = 18901


def get_swaysock():
    socks = sorted(glob.glob("/tmp/sway-ipc.*.sock"), key=os.path.getmtime, reverse=True)
    return socks[0] if socks else "/tmp/sway-ipc.sock"


def wl_env():
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": "/tmp",
        "WAYLAND_DISPLAY": "wayland-1",
        "SWAYSOCK": get_swaysock(),
        "DISPLAY": ":0",
        "PULSE_SERVER": "127.0.0.1",
        "DBUS_SESSION_BUS_ADDRESS": "unix:path=/tmp/omarchy-dbus.sock",
    }


def run_bg(cmd):
    subprocess.Popen(
        cmd, shell=True, env=wl_env(),
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True,
    )


def setup_pulse_mic_source():
    env = wl_env()
    try:
        out = subprocess.check_output(["pactl", "list", "short", "modules"], env=env, text=True, stderr=subprocess.DEVNULL)
        for line in out.splitlines():
            if "omarchy_mic" in line or "omarchy-mic.pcm" in line:
                mod_id = line.split()[0]
                subprocess.run(["pactl", "unload-module", mod_id], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

    try:
        if os.path.exists(MIC_FIFO):
            os.remove(MIC_FIFO)
    except Exception:
        pass

    # Let PulseAudio create the FIFO itself under its own UID, then chmod 666
    fifo_for_pa = TERMUX_MIC_FIFO if os.path.exists("/data/data/com.termux") or os.path.exists("/tmp/wayland-1") else MIC_FIFO
    subprocess.run(
        [
            "pactl", "load-module", "module-pipe-source",
            "source_name=omarchy_mic",
            f"file={fifo_for_pa}",
            "format=s16le", "rate=16000", "channels=1",
        ],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(0.3)
    try:
        os.chmod(MIC_FIFO, 0o666)
    except Exception:
        pass
    subprocess.run(["pactl", "set-default-source", "omarchy_mic"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["pactl", "set-source-mute", "omarchy_mic", "0"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def mic_server():
    setup_pulse_mic_source()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", MIC_PORT))
    srv.listen(4)
    print(f"[OmarchyDeckBridge] Mic PCM server listening on 127.0.0.1:{MIC_PORT}", flush=True)

    while True:
        conn, _ = srv.accept()
        fd = None
        try:
            # Open FIFO non-blocking first or RDWR so writer never stalls if PA hasn't read yet
            fd = os.open(MIC_FIFO, os.O_RDWR | os.O_NONBLOCK)
            while True:
                data = conn.recv(4096)
                if not data:
                    break
                try:
                    os.write(fd, data)
                except BlockingIOError:
                    pass
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass
            if fd is not None:
                try:
                    os.close(fd)
                except Exception:
                    pass


def handle_key(msg):
    code = msg.get("key", "")
    sup = bool(msg.get("super", False))
    ctrl = bool(msg.get("ctrl", False))
    alt = bool(msg.get("alt", False))
    shift = bool(msg.get("shift", False))

    if sup:
        if code == "space": run_bg("/usr/local/bin/omarchy-menu-ui apps"); return
        if code == "m": run_bg("/usr/local/bin/omarchy-menu-ui"); return
        if code == "Return": run_bg("alacritty"); return
        if code in ("w", "q"): run_bg("swaymsg kill"); return
        if code == "f": run_bg("swaymsg fullscreen toggle"); return
        if code == "t": run_bg("swaymsg floating toggle"); return
        if code == "i": run_bg("/usr/local/bin/omarchy-toggle-ime"); return
        if code in ("1", "2", "3", "4", "5"): run_bg(f"swaymsg workspace number {code}"); return
        if code in ("Left", "Right", "Up", "Down"): run_bg(f"swaymsg focus {code.lower()}"); return

    args = ["wtype"]
    if ctrl: args += ["-M", "ctrl"]
    if alt: args += ["-M", "alt"]
    if shift: args += ["-M", "shift"]
    args += ["-k", code]
    if shift: args += ["-m", "shift"]
    if alt: args += ["-m", "alt"]
    if ctrl: args += ["-m", "ctrl"]
    subprocess.Popen(args, env=wl_env(), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def handle_cmd(msg):
    action = msg.get("cmd", "")
    mapping = {
        "apps": "/usr/local/bin/omarchy-menu-ui apps",
        "menu": "/usr/local/bin/omarchy-menu-ui",
        "terminal": "alacritty",
        "kill": "swaymsg kill",
        "fullscreen": "swaymsg fullscreen toggle",
        "ime": "/usr/local/bin/omarchy-toggle-ime",
        "voice": "/usr/local/bin/omarchy-voice-toggle",
        "phone_toggle": "/usr/local/bin/omarchy-phone-mode toggle",
    }
    if action in mapping:
        run_bg(mapping[action])


def cmd_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", CMD_PORT))
    srv.listen(16)
    print(f"[OmarchyDeckBridge] Command server listening on 127.0.0.1:{CMD_PORT}", flush=True)

    while True:
        conn, _ = srv.accept()
        try:
            buf = b""
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    try:
                        msg = json.loads(line.decode("utf-8"))
                        if msg.get("type") == "key":
                            handle_key(msg)
                        elif msg.get("type") == "cmd":
                            handle_cmd(msg)
                    except Exception:
                        pass
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass


if __name__ == "__main__":
    t1 = threading.Thread(target=mic_server, daemon=True)
    t1.start()
    cmd_server()
