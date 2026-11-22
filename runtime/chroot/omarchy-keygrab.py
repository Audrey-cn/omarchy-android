#!/usr/bin/env python3
"""
Official Omarchy Cyberdeck Hardware Keyboard Grabber & Keybinding Engine (EVIOCGRAB)
Matches official basecamp/omarchy tiling-v2.conf & utilities.conf keybindings:
- Super + Space           : Walker App Launcher ( Search...)
- Super + Alt + Space     : Official Omarchy Menu (Go... Apps/Learn/Trigger/Style/Setup/About/System)
- Super + Ctrl + Space    : Next Theme Wallpaper
- Super + Shift + Space   : Toggle Top Bar
- Super + Return          : Alacritty Terminal
- Super + W               : Close Active Window
- Super + K               : Keybindings Cheat Sheet
- Super + J               : Toggle Split Orientation
- Super + T               : Toggle Floating/Tiling
- Super + F               : Fullscreen
- Super + Arrow / H,J,K,L : Focus Left/Right/Up/Down
- Super + Shift + Arrow   : Swap/Move Window
- Super + 1..5            : Switch Workspaces 1..5
- Super + Tab             : Next Workspace
- Alt + Tab               : Cycle Next Window
- Super + Escape          : Omarchy System Menu
- Super + Ctrl + Esc      : Toggle Keyboard Exclusive Grab (Omarchy <-> Android/Termux)
"""
import os
import sys
import json
import time
import signal
import select
import subprocess
import evdev
from evdev import ecodes

MODIFIER_CFG = "/root/.config/omarchy/modifier.json"
KEYPROBE_FLAG = "/tmp/omarchy-keyprobe.active"
KEYPROBE_OUT = "/tmp/omarchy-keyprobe.last"
_mod_cache = {"mtime": -1, "mode": "super+alt", "caps_as_super": False}

def get_modifier_cfg():
    try:
        st = os.stat(MODIFIER_CFG)
        if st.st_mtime != _mod_cache["mtime"]:
            data = json.load(open(MODIFIER_CFG, encoding="utf-8"))
            _mod_cache["mtime"] = st.st_mtime
            _mod_cache["mode"] = data.get("mode", "super+alt")
            _mod_cache["caps_as_super"] = bool(data.get("caps_as_super", False))
    except Exception:
        pass
    return _mod_cache["mode"], _mod_cache["caps_as_super"]

INTERNAL_DEVICES = {
    "goodix_ts", "uinput-goodix", "gpio-keys", "qpnp_pon",
    "qti-haptics", "sm8150-tavil-snd-card Button Jack",
    "sm8150-tavil-snd-card Headset Jack"
}

KEYMAP = {
    ecodes.KEY_ESC: "Escape",
    ecodes.KEY_1: "1", ecodes.KEY_2: "2", ecodes.KEY_3: "3", ecodes.KEY_4: "4",
    ecodes.KEY_5: "5", ecodes.KEY_6: "6", ecodes.KEY_7: "7", ecodes.KEY_8: "8",
    ecodes.KEY_9: "9", ecodes.KEY_0: "0",
    ecodes.KEY_MINUS: "minus", ecodes.KEY_EQUAL: "equal",
    ecodes.KEY_BACKSPACE: "BackSpace", ecodes.KEY_TAB: "Tab",
    ecodes.KEY_Q: "q", ecodes.KEY_W: "w", ecodes.KEY_E: "e", ecodes.KEY_R: "r",
    ecodes.KEY_T: "t", ecodes.KEY_Y: "y", ecodes.KEY_U: "u", ecodes.KEY_I: "i",
    ecodes.KEY_O: "o", ecodes.KEY_P: "p",
    ecodes.KEY_LEFTBRACE: "bracketleft", ecodes.KEY_RIGHTBRACE: "bracketright",
    ecodes.KEY_ENTER: "Return", ecodes.KEY_LEFTCTRL: "Control_L",
    ecodes.KEY_A: "a", ecodes.KEY_S: "s", ecodes.KEY_D: "d", ecodes.KEY_F: "f",
    ecodes.KEY_G: "g", ecodes.KEY_H: "h", ecodes.KEY_J: "j", ecodes.KEY_K: "k",
    ecodes.KEY_L: "l",
    ecodes.KEY_SEMICOLON: "semicolon", ecodes.KEY_APOSTROPHE: "apostrophe",
    ecodes.KEY_GRAVE: "grave", ecodes.KEY_LEFTSHIFT: "Shift_L",
    ecodes.KEY_BACKSLASH: "backslash",
    ecodes.KEY_Z: "z", ecodes.KEY_X: "x", ecodes.KEY_C: "c", ecodes.KEY_V: "v",
    ecodes.KEY_B: "b", ecodes.KEY_N: "n", ecodes.KEY_M: "m",
    ecodes.KEY_COMMA: "comma", ecodes.KEY_DOT: "period", ecodes.KEY_SLASH: "slash",
    ecodes.KEY_RIGHTSHIFT: "Shift_R", ecodes.KEY_LEFTALT: "Alt_L",
    ecodes.KEY_SPACE: "space", ecodes.KEY_CAPSLOCK: "Escape",
    ecodes.KEY_F1: "F1", ecodes.KEY_F2: "F2", ecodes.KEY_F3: "F3", ecodes.KEY_F4: "F4",
    ecodes.KEY_F5: "F5", ecodes.KEY_F6: "F6", ecodes.KEY_F7: "F7", ecodes.KEY_F8: "F8",
    ecodes.KEY_F9: "F9", ecodes.KEY_F10: "F10", ecodes.KEY_F11: "F11", ecodes.KEY_F12: "F12",
    ecodes.KEY_RIGHTCTRL: "Control_R", ecodes.KEY_RIGHTALT: "Alt_R",
    ecodes.KEY_HOME: "Home", ecodes.KEY_UP: "Up", ecodes.KEY_PAGEUP: "Prior",
    ecodes.KEY_LEFT: "Left", ecodes.KEY_RIGHT: "Right", ecodes.KEY_END: "End",
    ecodes.KEY_DOWN: "Down", ecodes.KEY_PAGEDOWN: "Next",
    ecodes.KEY_INSERT: "Insert", ecodes.KEY_DELETE: "Delete",
    ecodes.KEY_LEFTMETA: "Super_L", ecodes.KEY_RIGHTMETA: "Super_R",
}

WORKSPACES = {
    ecodes.KEY_1: "1",
    ecodes.KEY_2: "2",
    ecodes.KEY_3: "3",
    ecodes.KEY_4: "4",
    ecodes.KEY_5: "5",
}

def run_cmd(cmd):
    import glob
    socks = sorted(glob.glob("/tmp/sway-ipc.*.sock"), key=os.path.getmtime, reverse=True)
    swaysock = socks[0] if socks else "/tmp/sway-ipc.sock"
    env = {
        **os.environ,
        "DISPLAY": ":0",
        "XDG_RUNTIME_DIR": "/tmp",
        "WAYLAND_DISPLAY": "wayland-1",
        "SWAYSOCK": swaysock,
        "I3SOCK": swaysock,
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8"
    }
    subprocess.Popen(cmd.replace("i3-msg", "swaymsg"), shell=True, env=env)

def handle_omarchy_hotkey(code, super_held, alt_held, ctrl_held, shift_held, caps_held=False):
    mode, caps_as_super = get_modifier_cfg()
    if mode == "super":
        mod = super_held or (caps_as_super and caps_held)
    elif mode == "alt":
        mod = alt_held or (caps_as_super and caps_held)
    else:
        mod = super_held or alt_held or (caps_as_super and caps_held)
    if not mod:
        return False

    # Super + Alt + Space OR Alt + M OR Alt + Ctrl + Space -> Official Omarchy Control Menu
    if (super_held and alt_held and code == ecodes.KEY_SPACE) or (mod and ctrl_held and not shift_held and code == ecodes.KEY_SPACE) or (mod and not ctrl_held and not shift_held and code == ecodes.KEY_M):
        run_cmd("/usr/local/bin/omarchy-menu-ui")
        return True
    # Mod + Ctrl + Shift + Space -> Theme Selector Menu
    if mod and ctrl_held and shift_held and code == ecodes.KEY_SPACE:
        run_cmd("/usr/local/bin/omarchy-menu-ui theme")
        return True
    # Mod + Shift + Space -> Next Wallpaper
    if mod and shift_held and not ctrl_held and code == ecodes.KEY_SPACE:
        run_cmd("/usr/local/bin/omarchy-theme-engine.py next-bg")
        return True
    # Mod + Space (Alt+Space or Super+Space) -> Native Omarchy App Grid
    if mod and not ctrl_held and not shift_held and code == ecodes.KEY_SPACE:
        run_cmd("/usr/local/bin/omarchy-menu-ui apps")
        return True
    # Mod + I (Alt+I or Super+I) -> Toggle Chinese Pinyin IME (EN <-> 拼)
    if mod and not ctrl_held and not shift_held and code == ecodes.KEY_I:
        run_cmd("/usr/local/bin/omarchy-toggle-ime")
        return True
    # Mod + Ctrl + R -> Cycle Touchpad Rotation (0 / 90 / 180 / 270)
    if mod and ctrl_held and code == ecodes.KEY_R:
        rot_file = "/root/.config/omarchy/touchpad-rotation"
        cur = 0
        try:
            cur = int(open(rot_file).read().strip())
        except Exception:
            pass
        nxt = {0: 90, 90: 270, 270: 180, 180: 0}.get(cur, 0)
        os.makedirs(os.path.dirname(rot_file), exist_ok=True)
        open(rot_file, "w").write(str(nxt))
        run_cmd(f"notify-send '触控板方向已切换' '当前旋转角度: {nxt}°' 2>/dev/null || true")
        return True

    # Mod + Return -> Terminal
    if code == ecodes.KEY_ENTER:
        run_cmd("alacritty")
        return True
    # Mod + W or Mod + Q -> Close Window
    if code in (ecodes.KEY_W, ecodes.KEY_Q):
        run_cmd("i3-msg kill")
        return True
    # Mod + K -> Keybindings Cheat Sheet
    if code == ecodes.KEY_K and not shift_held:
        run_cmd("/usr/local/bin/omarchy-menu-ui keybindings")
        return True
    # Mod + J -> Toggle Split Orientation
    if code == ecodes.KEY_J and not shift_held:
        run_cmd("i3-msg layout toggle split")
        return True
    # Mod + T -> Toggle Floating/Tiling or Mod+Ctrl+T -> Btop
    if code == ecodes.KEY_T:
        if ctrl_held:
            run_cmd("alacritty -e btop --utf-force")
        else:
            run_cmd("i3-msg floating toggle")
        return True
    # Mod + F -> Fullscreen, Mod+Shift+F -> File Manager
    if code == ecodes.KEY_F:
        if shift_held:
            run_cmd("thunar")
        else:
            run_cmd("i3-msg fullscreen toggle")
        return True
    # Mod + B / Mod + N / Mod + E
    if code == ecodes.KEY_B:
        run_cmd("alacritty -e btop --utf-force")
        return True
    if code == ecodes.KEY_N:
        run_cmd("alacritty -e nvim")
        return True
    if code == ecodes.KEY_E:
        run_cmd("thunar")
        return True
    # Mod + Tab -> Next/Prev Workspace
    if code == ecodes.KEY_TAB:
        run_cmd("i3-msg workspace next" if not shift_held else "i3-msg workspace prev")
        return True
    # Mod + Arrow Keys -> Focus / Swap Window
    if code == ecodes.KEY_LEFT:
        run_cmd(f"i3-msg {'move' if shift_held else 'focus'} left")
        return True
    if code == ecodes.KEY_DOWN:
        run_cmd(f"i3-msg {'move' if shift_held else 'focus'} down")
        return True
    if code == ecodes.KEY_UP:
        run_cmd(f"i3-msg {'move' if shift_held else 'focus'} up")
        return True
    if code == ecodes.KEY_RIGHT:
        run_cmd(f"i3-msg {'move' if shift_held else 'focus'} right")
        return True
    # Mod + 1..5 -> Switch/Move Workspace
    if code in WORKSPACES:
        ws = WORKSPACES[code]
        if shift_held:
            run_cmd(f"i3-msg 'move container to workspace \"{ws}\"; workspace \"{ws}\"'")
        else:
            run_cmd(f"i3-msg 'workspace \"{ws}\"'")
        return True
    # Mod + Escape -> System Menu
    if code in (ecodes.KEY_ESC, ecodes.KEY_CAPSLOCK) and not ctrl_held:
        run_cmd("/usr/local/bin/omarchy-menu-ui system")
        return True
    return False

def is_external_keyboard(dev):
    if dev.name in INTERNAL_DEVICES:
        return False
    caps = dev.capabilities()
    if ecodes.EV_KEY not in caps:
        return False
    keys = caps[ecodes.EV_KEY]
    return ecodes.KEY_A in keys and ecodes.KEY_ENTER in keys

def watch_and_grab():
    os.environ["DISPLAY"] = ":0"
    devices = {}
    grabbed_state = True
    super_held = False
    alt_held = False
    ctrl_held = False
    shift_held = False
    caps_held = False

    def toggle_grab_signal(signum, frame):
        nonlocal grabbed_state
        if grabbed_state:
            for d in devices.values():
                try: d.ungrab()
                except: pass
            grabbed_state = False
        else:
            for d in devices.values():
                try: d.grab()
                except: pass
            grabbed_state = True

    signal.signal(signal.SIGUSR1, toggle_grab_signal)

    while True:
        try:
            for path in evdev.list_devices():
                if path in devices:
                    continue
                try:
                    dev = evdev.InputDevice(path)
                    if is_external_keyboard(dev):
                        devices[path] = dev
                        if grabbed_state:
                            try:
                                dev.grab()
                                print(f"[Omarchy KeyGrab] GRABBED {dev.name} ({path}).", flush=True)
                            except Exception:
                                pass
                except Exception:
                    pass
            if not devices:
                time.sleep(1.5)
                continue
            r, _, _ = select.select(list(devices.values()), [], [], 1.0)
            for dev in r:
                try:
                    rel_x = 0
                    rel_y = 0
                    for event in dev.read():
                        if event.type == ecodes.EV_REL and grabbed_state:
                            if event.code == ecodes.REL_X:
                                rel_x += event.value
                            elif event.code == ecodes.REL_Y:
                                rel_y += event.value
                            elif event.code == ecodes.REL_WHEEL:
                                btn = "4" if event.value > 0 else "5"
                                subprocess.Popen(["xdotool", "click", btn])
                            continue
                        if event.type == ecodes.EV_SYN and (rel_x != 0 or rel_y != 0) and grabbed_state:
                            rot = 0
                            try:
                                rot = int(open("/root/.config/omarchy/touchpad-rotation").read().strip())
                            except Exception:
                                pass
                            if rot == 90:
                                dx, dy = rel_y, -rel_x
                            elif rot == 270:
                                dx, dy = -rel_y, rel_x
                            elif rot == 180:
                                dx, dy = -rel_x, -rel_y
                            else:
                                dx, dy = rel_x, rel_y
                            subprocess.Popen(["xdotool", "mousemove_relative", "--", str(int(dx * 1.6)), str(int(dy * 1.6))])
                            rel_x = 0
                            rel_y = 0
                            continue

                        if event.type != ecodes.EV_KEY:
                            continue
                        code = event.code
                        val = event.value

                        # Forward M15 integrated touchpad left/right/middle mouse clicks!
                        if code in (ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE) and grabbed_state:
                            btn = "1" if code == ecodes.BTN_LEFT else ("3" if code == ecodes.BTN_RIGHT else "2")
                            if val == 1:
                                subprocess.Popen(["xdotool", "mousedown", btn])
                            elif val == 0:
                                subprocess.Popen(["xdotool", "mouseup", btn])
                            continue

                        mode, caps_as_super = get_modifier_cfg()

                        # Live Key Probe reporting when Keyboard Settings panel is open
                        if val == 1 and os.path.exists(KEYPROBE_FLAG):
                            try:
                                kname = ecodes.KEY.get(code, f"CODE_{code}")
                                if isinstance(kname, list):
                                    kname = kname[0]
                                with open(KEYPROBE_OUT, "w", encoding="utf-8") as pf:
                                    json.dump({"ts": time.time(), "code": code, "name": kname, "dev": dev.name}, pf)
                            except Exception:
                                pass

                        if code in (ecodes.KEY_LEFTMETA, ecodes.KEY_RIGHTMETA):
                            super_held = (val != 0)
                            if mode == "alt" and grabbed_state:
                                ksym = "Super_L" if code == ecodes.KEY_LEFTMETA else "Super_R"
                                if val == 1: subprocess.Popen(["xdotool", "keydown", ksym])
                                elif val == 0: subprocess.Popen(["xdotool", "keyup", ksym])
                            continue
                        if code in (ecodes.KEY_LEFTALT, ecodes.KEY_RIGHTALT):
                            alt_held = (val != 0)
                            if mode == "super" and grabbed_state:
                                ksym = "Alt_L" if code == ecodes.KEY_LEFTALT else "Alt_R"
                                if val == 1: subprocess.Popen(["xdotool", "keydown", ksym])
                                elif val == 0: subprocess.Popen(["xdotool", "keyup", ksym])
                            continue
                        if code == ecodes.KEY_CAPSLOCK and caps_as_super:
                            caps_held = (val != 0)
                            continue
                        if code in (ecodes.KEY_LEFTCTRL, ecodes.KEY_RIGHTCTRL):
                            ctrl_held = (val != 0)
                            if grabbed_state:
                                if val == 1: subprocess.Popen(["xdotool", "keydown", "Control_L"])
                                elif val == 0: subprocess.Popen(["xdotool", "keyup", "Control_L"])
                            continue
                        if code in (ecodes.KEY_LEFTSHIFT, ecodes.KEY_RIGHTSHIFT):
                            shift_held = (val != 0)
                            if grabbed_state:
                                if val == 1: subprocess.Popen(["xdotool", "keydown", "Shift_L"])
                                elif val == 0: subprocess.Popen(["xdotool", "keyup", "Shift_L"])
                            continue

                        # Direct hardware hotkey toggle for grab mode: Super + Ctrl + Esc
                        if val == 1 and (super_held or (caps_as_super and caps_held)) and ctrl_held and code in (ecodes.KEY_ESC, ecodes.KEY_CAPSLOCK):
                            toggle_grab_signal(None, None)
                            continue

                        if val == 1 and (super_held or alt_held or (caps_as_super and caps_held)):
                            if handle_omarchy_hotkey(code, super_held, alt_held, ctrl_held, shift_held, caps_held):
                                continue

                        if not grabbed_state:
                            continue

                        ksym = KEYMAP.get(code)
                        if not ksym:
                            continue
                        if val == 1:
                            subprocess.Popen(["xdotool", "keydown", ksym])
                        elif val == 0:
                            subprocess.Popen(["xdotool", "keyup", ksym])
                except OSError:
                    try: dev.ungrab()
                    except: pass
                    devices.pop(dev.path, None)
        except Exception:
            time.sleep(1)

if __name__ == "__main__":
    watch_and_grab()
