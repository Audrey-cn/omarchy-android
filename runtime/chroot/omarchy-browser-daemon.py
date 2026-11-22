#!/usr/bin/env python3
"""
Host-side Wayland Chromium Bridge Daemon (runs on Android host outside chroot)
Watches /data/data/com.termux/files/usr/tmp/omarchy-browser-queue/ for launch requests
written by Ubuntu chroot (e.g. ChatGPT Desktop, Claude Desktop, Chromium WebApps),
and launches native Termux Chromium 158 directly on wayland-1 as Termux UID!
"""
import os
import time
import glob
import json
import socket
import subprocess

QUEUE_DIR = "/data/data/com.termux/files/usr/tmp/omarchy-browser-queue"
os.makedirs(QUEUE_DIR, exist_ok=True)
os.chmod(QUEUE_DIR, 0o777)

TERMUX_PREFIX = "/data/data/com.termux/files/usr"
TERMUX_HOME = "/data/data/com.termux/files/home"
CHROMIUM_LIB = f"{TERMUX_PREFIX}/lib/chromium"
CHROMIUM_BIN = f"{TERMUX_PREFIX}/bin/chromium-browser"
if not os.path.exists(CHROMIUM_BIN):
    CHROMIUM_BIN = f"{TERMUX_PREFIX}/bin/chromium"

try:
    st = os.stat("/data/data/com.termux")
    TERMUX_UID, TERMUX_GID = st.st_uid, st.st_gid
except Exception:
    TERMUX_UID, TERMUX_GID = 10168, 10168


def is_local_proxy_up():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.3)
    try:
        s.connect(("127.0.0.1", 18888))
        return True
    except Exception:
        return False
    finally:
        try:
            s.close()
        except Exception:
            pass


def drop_privileges():
    os.setsid()
    os.setgid(TERMUX_GID)
    os.setuid(TERMUX_UID)


def launch_chromium(args):
    env = {
        "PATH": f"{TERMUX_PREFIX}/bin:/system/bin",
        "HOME": TERMUX_HOME,
        "PREFIX": TERMUX_PREFIX,
        "TMPDIR": f"{TERMUX_PREFIX}/tmp",
        "XDG_RUNTIME_DIR": f"{TERMUX_PREFIX}/tmp",
        "WAYLAND_DISPLAY": "wayland-1",
        "DISPLAY": ":0",
    }
    flags = [
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--in-process-gpu",
        "--disable-gpu-compositing",
        "--ozone-platform=wayland",
    ]
    has_proxy_arg = any(a.startswith("--proxy-server") for a in args)
    if not has_proxy_arg and is_local_proxy_up():
        flags.append("--proxy-server=http://127.0.0.1:18888")

    cmd = [CHROMIUM_BIN] + flags + args
    cwd = CHROMIUM_LIB if os.path.isdir(CHROMIUM_LIB) else TERMUX_HOME
    subprocess.Popen(
        cmd,
        env=env,
        cwd=cwd,
        preexec_fn=drop_privileges,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        close_fds=True,
    )


while True:
    for fpath in sorted(glob.glob(f"{QUEUE_DIR}/*.json")):
        try:
            with open(fpath, "r") as f:
                data = json.load(f)
            os.remove(fpath)
            args = data.get("args", [])
            launch_chromium(args)
        except Exception:
            try:
                os.remove(fpath)
            except Exception:
                pass
    time.sleep(0.25)
