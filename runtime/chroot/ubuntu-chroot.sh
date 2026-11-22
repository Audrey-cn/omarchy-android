#!/system/bin/sh
set -u
ROOT=/data/ubuntu
BB=/data/adb/magisk/busybox

# Remount /data with suid and dev enabled so sudo, ping, and device nodes work natively
mount -o remount,suid,dev /data 2>/dev/null || $BB mount -o remount,suid,dev /data 2>/dev/null

# Bind-mount ROOT onto itself so it is a genuine mountpoint
grep -q " $ROOT " /proc/mounts 2>/dev/null || $BB mount --bind "$ROOT" "$ROOT"

mnt_bind() {
    _src="$1"
    _dst="$ROOT/$2"
    mkdir -p "$_dst" 2>/dev/null
    grep -q " $_dst " /proc/mounts 2>/dev/null || $BB mount --bind "$_src" "$_dst" 2>/dev/null
}

mnt_fs() {
    _type="$1"
    _src="$2"
    _dst="$ROOT/$3"
    _opt="${4:-}"
    mkdir -p "$_dst" 2>/dev/null
    grep -q " $_dst " /proc/mounts 2>/dev/null && return 0
    if [ -n "$_opt" ]; then
        $BB mount -t "$_type" -o "$_opt" "$_src" "$_dst" 2>/dev/null
    else
        $BB mount -t "$_type" "$_src" "$_dst" 2>/dev/null
    fi
}

# Bind mount real /dev so /dev/kgsl-3d0, /dev/dri, and /dev/input/event* are directly accessible!
mnt_bind /dev dev
mnt_fs proc proc proc
mnt_fs sysfs sysfs sys
mnt_fs devpts devpts dev/pts "gid=5,mode=620"
mnt_fs tmpfs tmpfs dev/shm "mode=1777,size=2G"
mnt_fs tmpfs tmpfs run "mode=755"

# Bind mount Termux X11 tmp socket directory if present
if [ -d /data/data/com.termux/files/usr/tmp ]; then
    mkdir -p "$ROOT/tmp"
    grep -q " $ROOT/tmp " /proc/mounts 2>/dev/null || $BB mount --bind /data/data/com.termux/files/usr/tmp "$ROOT/tmp"
fi

# Bind mount /sdcard to /mnt/sdcard inside Ubuntu
if [ -d /data/media/0 ]; then
    mnt_bind /data/media/0 mnt/sdcard
fi

# Fix DNS & hosts
rm -f "$ROOT/etc/resolv.conf"
printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"
echo "raphael-omarchy" > "$ROOT/etc/hostname"
cat << 'EOF' > "$ROOT/etc/hosts"
127.0.0.1 localhost
127.0.1.1 raphael-omarchy
EOF

# Fix Android paranoid network & system groups for root & user inside Ubuntu
if ! grep -q "aid_inet" "$ROOT/etc/group" 2>/dev/null; then
    echo "aid_inet:x:3003:root,_apt" >> "$ROOT/etc/group"
    echo "aid_net_raw:x:3004:root,_apt" >> "$ROOT/etc/group"
    echo "aid_graphics:x:1003:root" >> "$ROOT/etc/group"
    echo "aid_input:x:1004:root" >> "$ROOT/etc/group"
    echo "aid_log:x:1007:root" >> "$ROOT/etc/group"
    echo "aid_adb:x:1011:root" >> "$ROOT/etc/group"
    echo "aid_sdcard_rw:x:1015:root" >> "$ROOT/etc/group"
    echo "aid_sdcard_r:x:1028:root" >> "$ROOT/etc/group"
    echo "aid_ext_data_rw:x:1078:root" >> "$ROOT/etc/group"
    echo "aid_ext_obb_rw:x:1079:root" >> "$ROOT/etc/group"
    echo "aid_net_bt_admin:x:3001:root" >> "$ROOT/etc/group"
    echo "aid_net_bt:x:3002:root" >> "$ROOT/etc/group"
    echo "aid_net_bw_stats:x:3006:root" >> "$ROOT/etc/group"
    echo "aid_readproc:x:3009:root" >> "$ROOT/etc/group"
    echo "aid_uhid:x:3011:root" >> "$ROOT/etc/group"
    echo "aid_readtracefs:x:3012:root" >> "$ROOT/etc/group"
fi

if ! grep -q "APT::Sandbox::User" "$ROOT/etc/apt/apt.conf.d/99android-sandbox" 2>/dev/null; then
    echo 'APT::Sandbox::User "root";' > "$ROOT/etc/apt/apt.conf.d/99android-sandbox"
fi

export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TMPDIR=/tmp

if [ "$#" -eq 0 ]; then
    exec $BB chroot "$ROOT" /bin/bash -l
else
    exec $BB chroot "$ROOT" /bin/bash -lc "$*"
fi
