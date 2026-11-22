#!/system/bin/sh
# ============================================================================
# OMARCHY NATIVE WAYLAND CYBERDECK LAUNCHER (Redmi K20 Pro Premium Edition)
# Starts Termux:X11 display server, Sway 1.12 / wlroots 0.20.2 Wayland Compositor,
# Native Chroot Ubuntu 24.04 Official Waybar, Swaybg, Wofi, and Evdev KeyGrab
# ============================================================================

TERMUX_UID=$(stat -c "%u" /data/data/com.termux/files/usr 2>/dev/null || echo 10168)
BB=/data/adb/magisk/busybox

echo "[1/5] Configuring Cyberdeck Landscape Display & Performance..."
settings put system accelerometer_rotation 0
settings put system user_rotation 1

mkdir -p /data/data/com.termux/files/usr/tmp
chmod 1777 /data/data/com.termux/files/usr/tmp
chown $TERMUX_UID:$TERMUX_UID /data/data/com.termux/files/usr/tmp

echo "[2/5] Starting Termux:X11 Display Server (:0) & Wayland Compositor (wayland-1)..."
for p in $(ps -ef | awk '/com\.termux\.x11|sway|swaybar|waybar|swaybg|wofi|i3|polybar|alacritty|rofi|omarchy-keygrab|omarchy-browser-daemon/ && !/awk|start-omarchy/ {print $2}'); do
    [ "$p" != "$$" ] && kill -9 "$p" 2>/dev/null || true
done
rm -rf /data/data/com.termux/files/usr/tmp/.X11-unix/X0 /data/data/com.termux/files/usr/tmp/.X0-lock /data/data/com.termux/files/usr/tmp/wayland* /data/data/com.termux/files/usr/tmp/sway-ipc* 2>/dev/null || true

$BB setuidgid $TERMUX_UID /data/data/com.termux/files/usr/bin/bash -c "
export PATH=/data/data/com.termux/files/usr/bin:\$PATH
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TMPDIR=/data/data/com.termux/files/usr/tmp
export XDG_RUNTIME_DIR=/data/data/com.termux/files/usr/tmp
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libtermux-exec.so
pulseaudio --start --exit-idle-time=-1 2>/dev/null || true
pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 2>/dev/null || true
termux-x11 :0 -ac >/data/data/com.termux/files/usr/tmp/x11.log 2>&1 &
"

for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if [ -S /data/data/com.termux/files/usr/tmp/.X11-unix/X0 ]; then
        echo "  -> X11 Backend :0 is READY!"
        break
    fi
    sleep 0.5
done

echo "[3/5] Opening Termux:X11 Fullscreen Viewport & Launching wlroots 0.20.2 Wayland Compositor..."
am start -n com.termux.x11/.MainActivity >/dev/null 2>&1

cp -f /data/ubuntu/root/.config/sway/config /data/data/com.termux/files/usr/tmp/omarchy-sway.conf 2>/dev/null || true
chmod 644 /data/data/com.termux/files/usr/tmp/omarchy-sway.conf 2>/dev/null || true

$BB setuidgid $TERMUX_UID /data/data/com.termux/files/usr/bin/bash -c "
export PATH=/data/data/com.termux/files/usr/bin:\$PATH
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TMPDIR=/data/data/com.termux/files/usr/tmp
export XDG_RUNTIME_DIR=/data/data/com.termux/files/usr/tmp
export DISPLAY=:0
export WLR_BACKENDS=x11
export WLR_RENDERER=pixman
export WLR_NO_HARDWARE_CURSORS=1
nohup sway -c /data/data/com.termux/files/usr/tmp/omarchy-sway.conf >/data/data/com.termux/files/usr/tmp/sway_termux.log 2>&1 &
"

for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if [ -S /data/data/com.termux/files/usr/tmp/wayland-1 ]; then
        chmod 777 /data/data/com.termux/files/usr/tmp/wayland-1 /data/data/com.termux/files/usr/tmp/sway-ipc.* 2>/dev/null || true
        echo "  -> Wayland Display wayland-1 is READY!"
        break
    fi
    sleep 0.5
done

echo "[4/5] Launching Host Chromium Bridge & Native Ubuntu 24.04 Official Waybar, Swaybg, Alacritty & Evdev KeyGrab..."
nohup /data/data/com.termux/files/usr/bin/python3 /data/adb/omarchy-browser-daemon.py >/data/data/com.termux/files/usr/tmp/browser-daemon.log 2>&1 &
/data/adb/ubuntu-chroot.sh '
export DISPLAY=:0
export XDG_RUNTIME_DIR=/tmp
export WAYLAND_DISPLAY=wayland-1
export SWAYSOCK=$(ls -t /tmp/sway-ipc.*.sock 2>/dev/null | head -n 1)
export PULSE_SERVER=127.0.0.1
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/omarchy-dbus.sock
export GTK_IM_MODULE=fcitx
export QT_IM_MODULE=fcitx
export XMODIFIERS=@im=fcitx
mkdir -p /run/dbus
dbus-daemon --system --fork 2>/dev/null || true
rm -f /tmp/omarchy-dbus.sock
dbus-daemon --session --address=unix:path=/tmp/omarchy-dbus.sock --fork --nopidfile 2>/dev/null || true
nohup fcitx5 -d --replace </dev/null >/tmp/fcitx5.log 2>&1 &
swaymsg "output X11-1 mode 2336x1080 scale 1.25" >/dev/null 2>&1 || true
nohup /usr/local/bin/omarchy-keygrab.py >/tmp/omarchy-keygrab.log 2>&1 &
/usr/local/bin/omarchy-wallpaper >/dev/null 2>&1 &
/usr/local/bin/launch-waybar >/dev/null 2>&1 &
nohup alacritty >/tmp/alacritty.log 2>&1 &
'

sleep 2
echo "[5/5] Omarchy Native Wayland Desktop is LIVE on Redmi K20 Pro!"
