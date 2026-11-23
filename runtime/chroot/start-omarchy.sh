#!/system/bin/sh
set -eu
wrapper=/data/omarchy-chroot/ubuntu-chroot.sh
display=$("$wrapper" --print-x11-display)
export OMARCHY_X11_DISPLAY="$display"
[ -S "/data/data/com.termux/files/usr/tmp/.X11-unix/X${display#:}" ] || {
    echo "Start termux-x11 $display inside Termux before launching the desktop." >&2
    exit 1
}
am start -n com.termux.x11/.MainActivity >/dev/null
exec "$wrapper" /bin/bash -es <<'SESSION'
# The public e550 image predates per-session signaling. Require the scoped
# command and library from the same trusted checkout before starting any UI.
python3 - <<'CAPABILITY'
from pathlib import Path
import sys
sys.dont_write_bytecode = True
launch = Path("/usr/local/bin/launch-waybar").resolve()
sys.path.insert(0, str(launch.parent))
from omarchy_session import stop_session_processes
if not callable(stop_session_processes) or any("stop_session_processes" not in p.read_text()
        for p in (launch, Path("/usr/local/bin/omarchy-theme-engine.py"))):
    raise SystemExit("Missing session-scoped desktop commands; install the current desktop component")
CAPABILITY
export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/omarchy-dbus.sock
export GTK_IM_MODULE=fcitx QT_IM_MODULE=fcitx XMODIFIERS=@im=fcitx
# Published installation media has an empty machine ID. Keep the generated ID
# across launches of this guest; concurrent launches share the same file lock.
(
    flock -x 9
    [[ -s /etc/machine-id ]] || dbus-uuidgen > /etc/machine-id
) 9>>/etc/machine-id
dbus-daemon --session --address="$DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile
/usr/local/bin/sway -c /root/.config/sway/config &
compositor=$!
trap 'kill "$compositor" 2>/dev/null || true' EXIT
for ((i=0;i<100;i++)); do
    for socket in /tmp/wayland-*; do
        if [[ -S "$socket" ]]; then export WAYLAND_DISPLAY=${socket##*/}; fi
    done
    for socket in /tmp/sway-ipc.*.sock; do
        if [[ -S "$socket" ]]; then export SWAYSOCK=$socket; fi
    done
    if [[ -n "$WAYLAND_DISPLAY" && -n "$SWAYSOCK" ]] && swaymsg -t get_outputs >/dev/null; then break; fi
    kill -0 "$compositor"
    sleep 0.1
done
: "${WAYLAND_DISPLAY:?Missing Wayland socket}" "${SWAYSOCK:?Missing Sway IPC}"
swaymsg -t get_outputs >/dev/null

config=$HOME/.config
state=$HOME/.local/state/omarchy/current
mkdir -p "$config" "$state"
theme=tokyo-night
if [[ -f "$state/theme.name" ]]; then theme=$(cat "$state/theme.name"); fi
[[ "$theme" != */* && -d "$HOME/.local/share/omarchy/themes/$theme" ]] || { echo "Unknown saved theme: $theme" >&2; exit 1; }
background=
if [[ -f "$state/background.path" ]]; then background=$(cat "$state/background.path"); fi
if [[ ! -s "$config/waybar/config.jsonc" || ! -s "$config/waybar/style.css" ||
      ! -s "$config/mako/config" || ! -s "$state/background.png" ]]; then
    render=$(mktemp -d /tmp/root-theme.XXXXXX)
    mkdir "$render/config" "$render/state"
    # Render the public image's original theme engine without touching live
    # configurations or invoking its old process-wide restart commands.
    # ubuntu-chroot.sh already made the outer namespace recursively private.
    # Preserve that property when cloning it: the chroot's / is a directory
    # within /data, so a second propagation change on / would return EINVAL.
    unshare --mount --propagation unchanged /bin/bash -es -- "$render" "$theme" "$background" <<'RENDER'
mount --bind "$1/config" /root/.config
mount --bind "$1/state" /root/.local/state/omarchy/current
for command in pidof swaybg swaymsg makoctl launch-waybar; do
    path=$(command -v "$command") || continue
    mount --bind /bin/true "$path"
done
/usr/local/bin/omarchy-theme-engine.py set "$2" "$3"
RENDER
    # Copy factory files only where a user has no file. Skip externally linked
    # configuration subtrees so a new session cannot write through them.
    python3 - "$render" "$config" "$state" <<'DEFAULTS'
from pathlib import Path
import shutil, sys
render, config, state = map(Path, sys.argv[1:])
for source, destination in ((render / "config", config), (render / "state", state)):
    for file in source.rglob("*"):
        if not file.is_file() or file.is_symlink():
            continue
        target = destination / file.relative_to(source)
        if target.exists() or target.is_symlink() or any(parent.is_symlink() for parent in target.parents):
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            with file.open("rb") as src, target.open("xb") as dst:
                shutil.copyfileobj(src, dst)
        except FileExistsError:
            continue
DEFAULTS
    rm -rf "$render"
fi

fcitx5 -d
mako &
notifications=$!
swaybg -i "$state/background.png" -m fill &
wallpaper=$!
waybar -c "$config/waybar/config.jsonc" -s "$config/waybar/style.css" >/tmp/waybar.log 2>&1 &
panel=$!
/usr/local/bin/omarchy-playwright-chromium about:blank &
browser=$!
for ((i=0;i<225;i++)); do
    for pid in "$compositor" "$notifications" "$wallpaper" "$panel" "$browser"; do kill -0 "$pid"; done
    if grep -q "Bar configured .* for output:" /tmp/waybar.log && gdbus call --session --timeout 2 --dest org.freedesktop.Notifications --object-path /fr/emersion/Mako --method fr.emersion.Mako.ListModes >/dev/null 2>&1 && swaymsg -t get_tree |
        jq -e '.. | objects | select((.app_id // "") | test("chrom";"i"))' >/dev/null; then
        echo "Root desktop ready: Sway, Waybar, wallpaper, Mako and Chromium"
        wait "$compositor"
        exit $?
    fi
    sleep 0.2
done
cat /tmp/waybar.log >&2
echo 'Root desktop startup timed out waiting for Waybar output, Mako or the Chromium window' >&2
exit 1
SESSION
