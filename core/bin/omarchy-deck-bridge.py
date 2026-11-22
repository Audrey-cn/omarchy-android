#!/usr/bin/env python3
"""
Omarchy Deck Bridge (Linux-side receiver for OmarchyDeck.apk)
Listens on TCP 127.0.0.1:18901 for newline-delimited JSON commands:
  - {"type": "key", "key": "...", "super": bool, "ctrl": bool, "alt": bool, "shift": bool}
  - {"type": "text", "text": "中文/英文语音识别文本"} -> Copies to Wayland clipboard & pastes into focused window
  - {"type": "cmd", "cmd": "apps|menu|terminal|kill|fullscreen|ime|phone_toggle"}
Works in BOTH Root (chroot) and Non-Root (proot) modes.
"""
import os
import glob
import json
import time
import socket
import subprocess

CMD_PORT = 18901
STATE_FILE = "/root/.local/state/omarchy/deck-orientation"
QUEUE_DIR = "/tmp/omarchy-browser-queue"


def get_swaysock():
    socks = sorted(glob.glob("/tmp/sway-ipc.*.sock"), key=os.path.getmtime, reverse=True)
    return socks[0] if socks else "/tmp/sway-ipc.sock"


def wl_env():
    return {
        **os.environ,
        "SHELL": "/bin/bash",
        "HOME": "/root",
        "USER": "root",
        "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
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


def focused_app_id():
    try:
        out = subprocess.check_output(["swaymsg", "-t", "get_tree"], env=wl_env(), text=True, stderr=subprocess.DEVNULL)
        tree = json.loads(out)
        stack = [tree]
        while stack:
            node = stack.pop()
            if node.get("focused"):
                return (node.get("app_id") or node.get("window_properties", {}).get("class") or "").lower()
            stack.extend(node.get("nodes", []))
            stack.extend(node.get("floating_nodes", []))
    except Exception:
        pass
    return ""


def handle_text(msg):
    text = msg.get("text", "")
    if not text:
        return
    env = wl_env()
    # Set Wayland clipboard synchronously and verify wl-paste matches before Ctrl+V (ZERO 1-step lag!)
    subprocess.Popen(["wl-copy", "--", text], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + 0.5
    while time.time() < deadline:
        try:
            cur = subprocess.check_output(["wl-paste", "--no-newline"], env=env, text=True, stderr=subprocess.DEVNULL, timeout=0.3)
            if cur == text:
                break
        except Exception:
            pass
        time.sleep(0.03)

    app = focused_app_id()
    is_term = any(t in app for t in ("alacritty", "kitty", "foot", "terminal", "wezterm"))
    if is_term:
        args = ["wtype", "-M", "ctrl", "-M", "shift", "-k", "v", "-m", "shift", "-m", "ctrl"]
    else:
        args = ["wtype", "-M", "ctrl", "-k", "v", "-m", "ctrl"]
    subprocess.Popen(args, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


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


def toggle_phone_orientation():
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    os.makedirs(QUEUE_DIR, exist_ok=True)
    cur = "landscape"
    if os.path.exists(STATE_FILE):
        cur = open(STATE_FILE).read().strip() or "landscape"
    nxt = "portrait" if cur == "landscape" else "landscape"
    open(STATE_FILE, "w").write(nxt)
    act = "rotate_portrait" if nxt == "portrait" else "rotate_landscape"
    with open(f"{QUEUE_DIR}/rot_{int(time.time()*1000)}.json", "w") as f:
        json.dump({"action": act}, f)
    time.sleep(0.8)
    scale = "1.35" if nxt == "portrait" else (open("/root/.config/omarchy/display-scale").read().strip() if os.path.exists("/root/.config/omarchy/display-scale") else "1.25")
    run_bg(f"swaymsg 'output X11-1 scale {scale}'")


def handle_cmd(msg):
    action = msg.get("cmd", "")
    if action == "phone_toggle":
        toggle_phone_orientation()
        return
    if action == "toggle_android_ime":
        os.makedirs(QUEUE_DIR, exist_ok=True)
        with open(f"{QUEUE_DIR}/ime_{int(time.time()*1000)}.json", "w") as f:
            json.dump({"action": "toggle_android_ime"}, f)
        return
    if action.startswith("ws_"):
        ws = action.split("_", 1)[1]
        if ws in ("1", "2", "3", "4", "5"):
            run_bg(f"swaymsg workspace number {ws}")
        return
    mapping = {
        "apps": "/usr/local/bin/omarchy-menu-ui apps",
        "menu": "/usr/local/bin/omarchy-menu-ui",
        "terminal": "alacritty",
        "kill": "swaymsg kill",
        "fullscreen": "swaymsg fullscreen toggle",
        "ime": "/usr/local/bin/omarchy-toggle-ime",
    }
    if action in mapping:
        run_bg(mapping[action])


def handle_resize(msg):
    w = int(msg.get("w", 0))
    h = int(msg.get("h", 0))
    if w < 300 or h < 300:
        return
    saved_scale = "1.25"
    try:
        if os.path.exists("/root/.config/omarchy/display-scale"):
            saved_scale = open("/root/.config/omarchy/display-scale").read().strip() or "1.25"
    except Exception:
        pass
    scale = "1.35" if h > w else saved_scale
    run_bg(f"swaymsg 'output X11-1 mode {w}x{h} scale {scale}'")


def cmd_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", CMD_PORT))
    srv.listen(16)
    print(f"[OmarchyDeckBridge] Listening on 127.0.0.1:{CMD_PORT}", flush=True)

    while True:
        conn, _ = srv.accept()
        try:
            buf = b""
            while True:
                chunk = conn.recv(8192)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    try:
                        msg = json.loads(line.decode("utf-8"))
                        t = msg.get("type")
                        if t == "key":
                            handle_key(msg)
                        elif t == "text":
                            handle_text(msg)
                        elif t == "cmd":
                            handle_cmd(msg)
                        elif t == "resize":
                            handle_resize(msg)
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
    cmd_server()
