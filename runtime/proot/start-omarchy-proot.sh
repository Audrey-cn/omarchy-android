#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# Omarchy on Android — Non-Root Launcher (Termux + Termux:X11 + Sway + proot)
# Works on ANY unrooted Android 10–15 phone / tablet / foldable!
# ============================================================================
set -euo pipefail

export TMPDIR="$PREFIX/tmp"
export XDG_RUNTIME_DIR="$PREFIX/tmp"

echo "[1/4] Starting PulseAudio & Termux:X11 (:0)..."
pkill -9 -f "termux-x11|sway|waybar|swaybg|fcitx5" 2>/dev/null || true
rm -rf "$TMPDIR/.X11-unix/X0" "$TMPDIR/.X0-lock" "$TMPDIR/wayland"* "$TMPDIR/sway-ipc"* 2>/dev/null || true

pulseaudio --start --exit-idle-time=-1 2>/dev/null || true
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null || true
termux-x11 :0 -ac >"$TMPDIR/x11.log" 2>&1 &

for _ in $(seq 1 16); do
  [[ -S "$TMPDIR/.X11-unix/X0" ]] && break
  sleep 0.4
done

echo "[2/4] Opening Termux:X11 & starting native Sway Wayland compositor..."
am start -n com.termux.x11/.MainActivity >/dev/null 2>&1 || true

UBUNTU_ROOT="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
cp -f "$UBUNTU_ROOT/root/.config/sway/config" "$TMPDIR/omarchy-sway.conf" 2>/dev/null || true

DISPLAY=:0 WLR_BACKENDS=x11 WLR_RENDERER=pixman WLR_NO_HARDWARE_CURSORS=1 \
  nohup sway -c "$TMPDIR/omarchy-sway.conf" >"$TMPDIR/sway.log" 2>&1 &

for _ in $(seq 1 16); do
  [[ -S "$TMPDIR/wayland-1" ]] && break
  sleep 0.4
done

echo "[3/4] Launching Omarchy Desktop inside Ubuntu proot..."
proot-distro login ubuntu --shared-tmp --bind "$TMPDIR:/tmp" -- bash -lc '
  export DISPLAY=:0
  export XDG_RUNTIME_DIR=/tmp
  export WAYLAND_DISPLAY=wayland-1
  export SWAYSOCK=$(ls -t /tmp/sway-ipc.*.sock 2>/dev/null | head -n 1)
  export PULSE_SERVER=127.0.0.1
  export LANG=zh_CN.UTF-8
  export LC_ALL=zh_CN.UTF-8
  export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/omarchy-dbus.sock
  export GTK_IM_MODULE=fcitx
  export QT_IM_MODULE=fcitx
  export XMODIFIERS=@im=fcitx
  rm -f /tmp/omarchy-dbus.sock
  dbus-daemon --session --address=unix:path=/tmp/omarchy-dbus.sock --fork --nopidfile 2>/dev/null || true
  nohup fcitx5 -d --replace </dev/null >/tmp/fcitx5.log 2>&1 &
  /usr/local/bin/omarchy-wallpaper >/dev/null 2>&1 &
  /usr/local/bin/launch-waybar >/dev/null 2>&1 &
  nohup alacritty >/tmp/alacritty.log 2>&1 &
'

echo "[4/4] Omarchy (Non-Root proot) is LIVE!"
