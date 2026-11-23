#!/usr/bin/env python3
"""
Official Omarchy Wayland Theme Engine & Switcher for Redmi K20 Pro
Runs natively on Wayland (Sway 1.12 / wlroots 0.20.2 + Official Waybar + Wofi/Fuzzel + Swaybg + Mako)
Reads official themes from /root/.local/share/omarchy/themes/<theme>/colors.toml
and backgrounds/*, converts WebP/JPG wallpaper, updates Waybar, Wofi, Fuzzel, Mako,
Alacritty, and Sway, and hot-reloads the live Wayland desktop!
"""
import os
import sys
import glob
import json
import subprocess

OMARCHY_PATH = "/root/.local/share/omarchy"
STATE_DIR = "/root/.local/state/omarchy/current"
THEME_DIR = f"{STATE_DIR}/theme"

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
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8"
    }

def parse_toml(path):
    res = {}
    if not os.path.exists(path):
        return res
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.strip()
            if v.startswith('"') and '"' in v[1:]:
                v = v[1:v.index('"', 1)]
            elif v.startswith("'") and "'" in v[1:]:
                v = v[1:v.index("'", 1)]
            else:
                v = v.split("#")[0].strip()
            if v:
                res[k] = v
    return res

def list_themes():
    themes = sorted(os.listdir(f"{OMARCHY_PATH}/themes"))
    return [t for t in themes if os.path.isdir(f"{OMARCHY_PATH}/themes/{t}")]

def apply_theme(theme_name, bg_file=None):
    tpath = f"{OMARCHY_PATH}/themes/{theme_name}"
    if not os.path.isdir(tpath):
        print(f"Theme {theme_name} not found")
        return False
    os.makedirs(THEME_DIR, exist_ok=True)
    os.makedirs("/root/.config/waybar", exist_ok=True)
    os.makedirs("/root/.config/wofi", exist_ok=True)
    os.makedirs("/root/.config/fuzzel", exist_ok=True)
    os.makedirs("/root/.config/mako", exist_ok=True)
    os.makedirs("/root/.config/sway", exist_ok=True)

    colors = parse_toml(f"{tpath}/colors.toml")
    bg = colors.get("background", "#1a1b26")
    fg = colors.get("foreground", "#a9b1d6")
    cursor = colors.get("cursor", "#c0caf5")
    accent = colors.get("accent", "#7aa2f7")
    sel_bg = colors.get("selection_background", accent)
    sel_fg = colors.get("selection_foreground", cursor)
    c = [colors.get(f"color{i}", fg) for i in range(16)]

    with open(f"{STATE_DIR}/theme.name", "w") as f:
        f.write(theme_name)

    # 1. Alacritty Theme
    alacritty_theme = f"""[colors.primary]
background = "{bg}"
foreground = "{fg}"

[colors.cursor]
text = "{bg}"
cursor = "{cursor}"

[colors.selection]
text = "{sel_fg}"
background = "{sel_bg}"

[colors.normal]
black   = "{c[0]}"
red     = "{c[1]}"
green   = "{c[2]}"
yellow  = "{c[3]}"
blue    = "{c[4]}"
magenta = "{c[5]}"
cyan    = "{c[6]}"
white   = "{c[7]}"

[colors.bright]
black   = "{c[8]}"
red     = "{c[9]}"
green   = "{c[10]}"
yellow  = "{c[11]}"
blue    = "{c[12]}"
magenta = "{c[13]}"
cyan    = "{c[14]}"
white   = "{c[15]}"
"""
    with open(f"{THEME_DIR}/alacritty.toml", "w") as f:
        f.write(alacritty_theme)
    if os.path.exists("/root/.config/alacritty/alacritty.toml"):
        os.utime("/root/.config/alacritty/alacritty.toml", None)

    # 2. Official Omarchy Waybar Config (1:1 match with official config/omarchy/shell.json)
    waybar_config = {
        "reload_style_on_change": True,
        "layer": "top",
        "position": "top",
        "spacing": 0,
        "height": 28,
        "modules-left": ["custom/omarchy", "sway/workspaces"],
        "modules-center": [
            "custom/ind-dnd",
            "custom/ind-night",
            "custom/ind-awake",
            "custom/ind-sudo",
            "custom/ind-rec",
            "clock",
            "custom/update"
        ],
        "modules-right": [
            "tray",
            "custom/ime",
            "custom/agents",
            "custom/bluetooth",
            "custom/network",
            "custom/audio",
            "custom/monitor",
            "custom/power"
        ],
        "sway/workspaces": {
            "disable-scroll": False,
            "all-outputs": True,
            "format": "{icon}",
            "format-icons": {
                "1": "1", "2": "2", "3": "3", "4": "4", "5": "5",
                "focused": "󱓻",
                "default": ""
            },
            "persistent-workspaces": {"1": [], "2": [], "3": [], "4": [], "5": []}
        },
        "custom/omarchy": {
            "format": "<span font='omarchy'>\ue900</span>",
            "on-click": "/usr/local/bin/omarchy-menu-ui",
            "on-click-right": "alacritty &",
            "tooltip-format": "Omarchy 主菜单 (Alt + M) · 右键打开终端"
        },
        "custom/ind-dnd": {
            "exec": "/usr/local/bin/omarchy-indicator status dnd",
            "return-type": "json",
            "interval": 5,
            "signal": 10,
            "on-click": "/usr/local/bin/omarchy-indicator toggle dnd"
        },
        "custom/ind-night": {
            "exec": "/usr/local/bin/omarchy-indicator status night",
            "return-type": "json",
            "interval": 5,
            "signal": 10,
            "on-click": "/usr/local/bin/omarchy-indicator toggle night"
        },
        "custom/ind-awake": {
            "exec": "/usr/local/bin/omarchy-indicator status awake",
            "return-type": "json",
            "interval": 5,
            "signal": 10,
            "on-click": "/usr/local/bin/omarchy-indicator toggle awake"
        },
        "custom/ind-sudo": {
            "exec": "/usr/local/bin/omarchy-indicator status sudo",
            "return-type": "json",
            "interval": 5,
            "signal": 10,
            "on-click": "/usr/local/bin/omarchy-indicator toggle sudo"
        },
        "custom/ind-rec": {
            "exec": "/usr/local/bin/omarchy-indicator status rec",
            "return-type": "json",
            "interval": 30,
            "signal": 10,
            "on-click": "/usr/local/bin/omarchy-indicator toggle rec"
        },
        "clock": {
            "format": "{:%A %H:%M}",
            "format-alt": "{:%m月%d日 W%V %Y}",
            "tooltip-format": "左键打开日历 · 右键切换格式",
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel calendar"
        },
        "custom/update": {
            "exec": "/usr/local/bin/omarchy-update-check",
            "return-type": "json",
            "interval": 1800,
            "signal": 11,
            "on-click": "/usr/local/bin/omarchy-menu-ui update"
        },
        "tray": {
            "icon-size": 15,
            "spacing": 8
        },
        "custom/ime": {
            "exec": "/usr/local/bin/omarchy-ime-status",
            "return-type": "json",
            "interval": 2,
            "signal": 9,
            "on-click": "/usr/local/bin/omarchy-toggle-ime",
            "on-click-right": "DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/omarchy-dbus.sock fcitx5-configtool &"
        },
        "custom/agents": {
            "format": "󰚩",
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel agents"
        },
        "custom/bluetooth": {
            "format": "󰂯",
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel bluetooth"
        },
        "custom/network": {
            "exec": "/usr/local/bin/omarchy-net-status",
            "return-type": "json",
            "interval": 5,
            "signal": 7,
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel network"
        },
        "custom/audio": {
            "format": "󰕾",
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel audio",
            "on-click-right": "PULSE_SERVER=127.0.0.1 pactl set-sink-mute @DEFAULT_SINK@ toggle"
        },
        "custom/monitor": {
            "format": "󰃠",
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel monitor"
        },
        "custom/power": {
            "exec": "cap=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo 100); echo \"󰁹 ${cap}%\"",
            "interval": 15,
            "tooltip": False,
            "on-click": "GDK_BACKEND=wayland /usr/local/bin/omarchy-panel power"
        }
    }
    with open("/root/.config/waybar/config.jsonc", "w") as f:
        json.dump(waybar_config, f, indent=2, ensure_ascii=False)

    bar_inset = 18
    try:
        if os.path.exists("/root/.config/omarchy/bar-inset"):
            bar_inset = max(0, min(48, int(open("/root/.config/omarchy/bar-inset").read().strip())))
    except Exception:
        pass

    waybar_css = f"""@define-color background {bg};
@define-color foreground {fg};
@define-color accent {accent};
@define-color muted {c[8]};

* {{
  background-color: @background;
  color: @foreground;
  border: none;
  border-radius: 0;
  min-height: 0;
  font-family: 'JetBrainsMono Nerd Font', 'Noto Sans CJK SC';
  font-size: 12px;
}}

.modules-left {{
  margin-left: {bar_inset}px;
}}

.modules-right {{
  margin-right: {bar_inset}px;
}}

#workspaces button {{
  all: initial;
  padding: 0 6px;
  margin: 0 1.5px;
  min-width: 9px;
  color: @foreground;
  font-family: 'JetBrainsMono Nerd Font';
  font-size: 12px;
}}

#workspaces button.focused {{
  color: @accent;
}}

#workspaces button.empty {{
  opacity: 0.45;
}}

#cpu,
#custom-battery,
#custom-audio,
#custom-network,
#custom-bluetooth,
#custom-agents,
#custom-sudo-toggle,
#custom-update,
#custom-omarchy {{
  min-width: 12px;
  margin: 0 7.5px;
}}

#custom-bluetooth,
#custom-agents {{
  color: @accent;
}}

#clock {{
  padding: 0 12px;
  font-weight: bold;
}}

#custom-ind-dnd, #custom-ind-night, #custom-ind-awake, #custom-ind-sudo, #custom-ind-rec {{
  padding: 0 6px;
  font-size: 13px;
}}

#custom-ind-dnd.inactive, #custom-ind-night.inactive, #custom-ind-awake.inactive, #custom-ind-sudo.inactive, #custom-ind-rec.inactive {{
  opacity: 0.42;
}}

#custom-ind-dnd.active, #custom-ind-night.active, #custom-ind-awake.active {{
  color: @accent;
  opacity: 1.0;
}}

#custom-ind-sudo.active {{
  color: #e0af68;
  opacity: 1.0;
}}

#custom-ime {{
  padding: 0 8px;
  margin: 0 4px;
  font-weight: bold;
}}

#custom-ime.pinyin {{
  color: @accent;
}}

#custom-agents, #custom-bluetooth, #custom-network, #custom-audio, #custom-monitor, #custom-power {{
  padding: 0 8px;
}}

tooltip {{
  padding: 4px 8px;
  border: 1px solid @accent;
}}
"""
    with open("/root/.config/waybar/style.css", "w") as f:
        f.write(waybar_css)

    # 3. Native Wayland Wofi CSS (Matches Official Omarchy Menu.qml dark card + crisp accent bar!)
    wofi_css = f"""window {{
    margin: 0px;
    border: 2px solid {accent};
    border-radius: 8px;
    background-color: {bg};
    font-family: 'JetBrainsMono Nerd Font', 'Noto Sans CJK SC', sans-serif;
    font-size: 13px;
}}

#outer-box {{
    margin: 12px;
    border: none;
    background-color: {bg};
}}

#input {{
    margin: 0px 0px 10px 0px;
    padding: 8px 12px;
    border: 1px solid alpha({accent}, 0.45);
    border-radius: 6px;
    color: {fg};
    background-color: alpha({fg}, 0.06);
    caret-color: {accent};
}}

#input:focus {{
    border: 1px solid {accent};
    color: {fg};
    background-color: alpha({fg}, 0.09);
}}

#inner-box {{
    margin: 0px;
    border: none;
    background-color: {bg};
}}

#scroll {{
    margin: 0px;
    border: none;
}}

#text {{
    margin: 2px 6px;
    border: none;
    color: {fg};
}}

#entry {{
    padding: 7px 10px;
    border-radius: 6px;
    border-left: 3px solid transparent;
    background-color: transparent;
}}

#entry:selected {{
    background-color: alpha({accent}, 0.20);
    border-left: 3px solid {accent};
}}

#entry:selected #text {{
    color: {fg};
    font-weight: bold;
}}
"""
    with open("/root/.config/wofi/style.css", "w") as f:
        f.write(wofi_css)

    # 3b. Native Wayland Wofi 3-Column App Grid CSS
    grid_css = wofi_css.replace("padding: 7px 10px;", "padding: 12px 14px; margin: 4px;")
    with open("/root/.config/wofi/grid.css", "w") as f:
        f.write(grid_css)

    # 4. Native Wayland Mako Notification Config
    mako_cfg = f"""font=JetBrainsMono Nerd Font 11
background-color={bg}
text-color={fg}
border-color={accent}
border-size=2
border-radius=0
padding=12
margin=10
default-timeout=4000
"""
    with open("/root/.config/mako/config", "w") as f:
        f.write(mako_cfg)

    # 5. Update Sway Wayland Compositor Config (Matches Omarchy looknfeel.conf & tiling-v2.conf)
    saved_scale = "1.5"
    try:
        if os.path.exists("/root/.config/omarchy/display-scale"):
            saved_scale = open("/root/.config/omarchy/display-scale").read().strip() or "1.5"
    except Exception:
        pass

    output_line = f"output X11-1 scale {saved_scale}"
    try:
        xr = subprocess.check_output(["xrandr"], env={**os.environ, "DISPLAY": os.environ.get("DISPLAY", ":0")}, text=True, stderr=subprocess.DEVNULL, timeout=2)
        m = re.search(r"current\s+(\d+)\s+x\s+(\d+)", xr)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
            if w >= 300 and h >= 300:
                output_line = f"output X11-1 mode {w}x{h} scale {saved_scale}"
    except Exception:
        pass

    if os.path.exists("/data/adb/ubuntu-chroot.sh"):
        exec_term = "/data/adb/ubuntu-chroot.sh 'WAYLAND_DISPLAY=wayland-1 XDG_RUNTIME_DIR=/tmp alacritty'"
        exec_apps = "/data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-menu-ui apps'"
        exec_menu = "/data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-menu-ui'"
        exec_keys = "/data/adb/ubuntu-chroot.sh '/usr/local/bin/omarchy-menu-ui keybindings'"
    else:
        exec_term = "alacritty"
        exec_apps = "/usr/local/bin/omarchy-menu-ui apps"
        exec_menu = "/usr/local/bin/omarchy-menu-ui"
        exec_keys = "/usr/local/bin/omarchy-menu-ui keybindings"

    sway_cfg = f"""set $mod Mod4
set $alt Mod1

font pango:JetBrainsMono Nerd Font 10
{output_line}

default_border pixel 2
default_floating_border pixel 2
gaps inner 5
gaps outer 10

# class                 border    backgr.   text    indicator child_border
client.focused          {accent}  {c[0]}    {fg}    {c[6]}    {accent}
client.focused_inactive {c[8]}    {bg}      {fg}    {c[8]}    {c[8]}
client.unfocused        {c[8]}    {bg}      {c[8]}  {c[8]}    {c[8]}
client.urgent           {c[1]}    {c[1]}    {bg}    {c[1]}    {c[1]}

floating_modifier $mod normal

# Official Omarchy Keybindings
bindsym $mod+Return exec {exec_term}
bindsym $mod+space exec {exec_apps}
bindsym $mod+$alt+space exec {exec_menu}
bindsym $mod+k exec {exec_keys}
bindsym $mod+w kill
bindsym $mod+q kill
bindsym $mod+j layout toggle split
bindsym $mod+t floating toggle
bindsym $mod+f fullscreen toggle

bindsym $mod+Left focus left
bindsym $mod+Down focus down
bindsym $mod+Up focus up
bindsym $mod+Right focus right
bindsym $alt+Tab focus right

bindsym $mod+Shift+Left move left
bindsym $mod+Shift+Down move down
bindsym $mod+Shift+Up move up
bindsym $mod+Shift+Right move right

bindsym $mod+1 workspace number 1
bindsym $mod+2 workspace number 2
bindsym $mod+3 workspace number 3
bindsym $mod+4 workspace number 4
bindsym $mod+5 workspace number 5
bindsym $mod+Tab workspace next
bindsym $mod+Shift+Tab workspace prev

bindsym $mod+Shift+1 move container to workspace number 1
bindsym $mod+Shift+2 move container to workspace number 2
bindsym $mod+Shift+3 move container to workspace number 3
bindsym $mod+Shift+4 move container to workspace number 4
bindsym $mod+Shift+5 move container to workspace number 5
"""
    with open("/root/.config/sway/config", "w") as f:
        f.write(sway_cfg)
    with open("/tmp/omarchy-sway.conf", "w") as f:
        f.write(sway_cfg)

    # 6. Convert & Apply Wallpaper using Native Wayland `swaybg`!
    bgs = sorted(glob.glob(f"{tpath}/backgrounds/*"))
    chosen_bg = bg_file if (bg_file and os.path.exists(bg_file)) else (bgs[0] if bgs else None)
    out_png = f"{STATE_DIR}/background.png"
    if chosen_bg:
        with open(f"{STATE_DIR}/background.path", "w") as f:
            f.write(chosen_bg)
        if chosen_bg.lower().endswith(".webp"):
            subprocess.run(["dwebp", chosen_bg, "-o", out_png], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            subprocess.run(["convert", chosen_bg, out_png], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    env = wl_env()
    # Reload Sway border colors & output scaling
    subprocess.run(["swaymsg", f"client.focused {accent} {c[0]} {fg} {c[6]} {accent}"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["swaymsg", f"client.focused_inactive {c[8]} {bg} {fg} {c[8]} {c[8]}"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["swaymsg", f"client.unfocused {c[8]} {bg} {c[8]} {c[8]} {c[8]}"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Restart swaybg on Wayland
    subprocess.run("for p in $(pidof swaybg 2>/dev/null); do kill -9 $p; done", shell=True)
    if os.path.exists(out_png):
        subprocess.Popen(["swaybg", "-i", out_png, "-m", "fill"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Restart Official Waybar & reload Mako on Wayland
    subprocess.run(["makoctl", "reload"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.Popen(["/usr/local/bin/launch-waybar"], env=env)
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Available themes:", ", ".join(list_themes()))
        sys.exit(0)
    cmd = sys.argv[1]
    if cmd == "list":
        for t in list_themes():
            print(t)
    elif cmd == "set" and len(sys.argv) >= 3:
        bg = sys.argv[3] if len(sys.argv) >= 4 else None
        apply_theme(sys.argv[2], bg)
    elif cmd == "next-bg":
        tname = "tokyo-night"
        if os.path.exists(f"{STATE_DIR}/theme.name"):
            tname = open(f"{STATE_DIR}/theme.name").read().strip()
        bgs = sorted(glob.glob(f"{OMARCHY_PATH}/themes/{tname}/backgrounds/*"))
        if bgs:
            cur = open(f"{STATE_DIR}/background.path").read().strip() if os.path.exists(f"{STATE_DIR}/background.path") else ""
            idx = (bgs.index(cur) + 1) % len(bgs) if cur in bgs else 0
            apply_theme(tname, bgs[idx])
