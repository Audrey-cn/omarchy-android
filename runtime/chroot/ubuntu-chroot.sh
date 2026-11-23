#!/system/bin/sh
# A private mount namespace and subreaper confine each foreground session.
set -eu
display=${OMARCHY_X11_DISPLAY-:0}
number=${display#:}
case "$number" in
    ""|*[!0-9]*) echo "OMARCHY_X11_DISPLAY must be : followed by digits" >&2; exit 2;;
esac
[ "$display" = ":$number" ] || { echo "OMARCHY_X11_DISPLAY must be : followed by digits" >&2; exit 2; }
if [ "${1:-}" = --print-x11-display ]; then
    printf '%s\n' "$display"
    exit 0
fi
base=/data/omarchy-chroot
root=$base/rootfs
bb=$(cat "$base/busybox.path")
[ "$(id -u)" = 0 ] && [ -f "$base/image.receipt" ] || exit 1
if [ "${1:-}" != --inside-namespace ]; then
    original=$(readlink /proc/self/ns/mnt)
    exec "$bb" unshare -m "$0" --inside-namespace "$original" "$@"
fi
shift
[ "$(readlink /proc/self/ns/mnt)" != "${1:?missing original namespace}" ] || exit 1
shift
"$bb" mount --make-rprivate /
"$bb" mount -t proc proc "$root/proc"
"$bb" mount --bind /sys "$root/sys"
"$bb" mount -o remount,bind,ro "$root/sys"
"$bb" mount -t tmpfs -o mode=755 tmpfs "$root/dev"
for node in null zero full random urandom tty; do
    touch "$root/dev/$node"
    "$bb" mount --bind "/dev/$node" "$root/dev/$node"
done
mkdir -p "$root/dev/shm" "$root/dev/pts"
"$bb" mount -t tmpfs -o mode=1777 tmpfs "$root/dev/shm"
"$bb" mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "$root/dev/pts"
ln -s pts/ptmx "$root/dev/ptmx"
ln -s /proc/self/fd "$root/dev/fd"
"$bb" mount -t tmpfs -o mode=755 tmpfs "$root/run"
cp "$base/supervise.py" "$root/run/omarchy-supervise.py"
"$bb" mount -t tmpfs -o mode=1777 tmpfs "$root/tmp"
if [ -d /data/data/com.termux/files/usr/tmp/.X11-unix ]; then
    mkdir "$root/tmp/.X11-unix"
    "$bb" mount --bind /data/data/com.termux/files/usr/tmp/.X11-unix "$root/tmp/.X11-unix"
fi
exec "$bb" chroot "$root" /usr/bin/env -i HOME=/root USER=root SHELL=/bin/bash TERM=xterm-256color OMARCHY_RUNTIME_KIND=chroot OMARCHY_RUNTIME_API=1 LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin DISPLAY="$display" OMARCHY_X11_DISPLAY="$display" XDG_RUNTIME_DIR=/tmp WLR_BACKENDS=x11 WLR_RENDERER=pixman WLR_NO_HARDWARE_CURSORS=1 /usr/bin/python3 /run/omarchy-supervise.py "$@"
