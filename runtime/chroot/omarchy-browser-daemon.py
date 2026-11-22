#!/usr/bin/env python3
"""
Host-side Wayland Chromium & Android System Bridge Daemon (runs on Android host outside chroot)
Watches /data/data/com.termux/files/usr/tmp/omarchy-browser-queue/ for:
  - Chromium launch requests ({"args": [...]})
  - Android system actions ({"action": "wifi_settings"|"bt_settings"|"bt_enable"|"bt_disable"|"go_home"})
And periodically writes Android Wi-Fi / Bluetooth status to omarchy-host-status.json.
"""
import os
import re
import time
import glob
import json
import socket
import subprocess

TMP_DIR = "/data/data/com.termux/files/usr/tmp"
QUEUE_DIR = f"{TMP_DIR}/omarchy-browser-queue"
STATUS_FILE = f"{TMP_DIR}/omarchy-host-status.json"
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


def sh(cmd):
    try:
        return subprocess.check_output(cmd, shell=True, stderr=subprocess.DEVNULL, text=True, timeout=3)
    except Exception:
        return ""


def refresh_host_status():
    info = {
        "wifi_enabled": False,
        "wifi_ssid": "",
        "wifi_speed": "",
        "wifi_rssi": "",
        "bt_enabled": False,
        "bt_name": "",
        "ts": time.time(),
    }
    w = sh("dumpsys wifi | grep -E 'Wi-Fi is|mWifiInfo' | head -n 5")
    if "Wi-Fi is enabled" in w:
        info["wifi_enabled"] = True
    m_ssid = re.search(r'SSID:\s*"([^"]+)"', w)
    if m_ssid and m_ssid.group(1) != "<unknown ssid>":
        info["wifi_ssid"] = m_ssid.group(1)
    m_spd = re.search(r'Link speed:\s*([0-9]+Mbps)', w)
    if m_spd:
        info["wifi_speed"] = m_spd.group(1)
    m_rssi = re.search(r'RSSI:\s*(-?[0-9]+)', w)
    if m_rssi:
        info["wifi_rssi"] = f"{m_rssi.group(1)} dBm"

    b = sh("dumpsys bluetooth_manager | grep -E 'enabled:|state:|name:' | head -n 6")
    if "enabled: true" in b or "state: ON" in b:
        info["bt_enabled"] = True
    m_bn = re.search(r'name:\s*(.+)', b)
    if m_bn:
        info["bt_name"] = m_bn.group(1).strip()

    try:
        tmp_f = STATUS_FILE + ".tmp"
        with open(tmp_f, "w", encoding="utf-8") as f:
            json.dump(info, f, ensure_ascii=False)
        os.chmod(tmp_f, 0o644)
        os.replace(tmp_f, STATUS_FILE)
    except Exception:
        pass


def handle_action(action):
    if action == "wifi_settings":
        subprocess.Popen(["am", "start", "-a", "android.settings.WIFI_SETTINGS"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    elif action == "bt_settings":
        subprocess.Popen(["am", "start", "-a", "android.settings.BLUETOOTH_SETTINGS"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    elif action == "bt_enable":
        subprocess.run(["cmd", "bluetooth_manager", "enable"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.6)
        refresh_host_status()
    elif action == "bt_disable":
        subprocess.run(["cmd", "bluetooth_manager", "disable"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.6)
        refresh_host_status()
    elif action == "go_home":
        subprocess.Popen(["am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    elif action == "refresh_status":
        refresh_host_status()


last_status_ts = 0.0
refresh_host_status()

while True:
    for fpath in sorted(glob.glob(f"{QUEUE_DIR}/*.json")):
        try:
            with open(fpath, "r") as f:
                data = json.load(f)
            os.remove(fpath)
            if "action" in data:
                handle_action(data["action"])
            else:
                launch_chromium(data.get("args", []))
        except Exception:
            try:
                os.remove(fpath)
            except Exception:
                pass
    now = time.time()
    if now - last_status_ts >= 5.0:
        refresh_host_status()
        last_status_ts = now
    time.sleep(0.25)
