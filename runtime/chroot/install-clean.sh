#!/system/bin/sh
# Import only the published clean ARM64 image into a newly claimed directory.
set -eu
umask 077
base=/data/omarchy-chroot
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
size=999745976
sha=88a1558fc93489016ad1c53fccb03ebc0f3986507dccf6b422de444d915f33c6
unpacked=2973377562
tag=desktop-ubuntu24-e55030353df6
url=https://github.com/Audrey-cn/omarchy-android/releases/download/$tag/rootfs.tar.zst
api_url=https://api.github.com/repos/Audrey-cn/omarchy-android/releases/assets/782730757
action=install
image=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dry-run|--inspect) action=${1#--};;
        --image) shift; image=${1:?missing image path};;
        *) echo "Unknown argument: $1" >&2; exit 2;;
    esac
    shift
done
[ "$action" != inspect ] || {
    printf 'guest=%s/rootfs\nimage=%s\nsha256=%s\nbytes=%s\nunpackedBytes=%s\n' "$base" "$tag" "$sha" "$size" "$unpacked"
    if [ -e "$base" ] || [ -L "$base" ]; then echo 'destination=occupied'; else echo 'destination=available'; fi
    exit 0
}
fail() { echo "$*" >&2; exit 1; }
repo=$(CDPATH='' cd -- "$here/../.." && pwd)
for input in core/bin/omarchy_session.py core/bin/launch-waybar core/bin/omarchy-theme-engine.py \
    core/bin/omarchy-menu-ui core/bin/omarchy-app-grid core/bin/omarchy-panel core/bin/omarchy-updates \
    core/bin/omarchy-indicator core/bin/omarchy-toggle-ime \
    core/config/component-ownership.json core/ai-suite/hermes-desktop-launch scripts/stage-core.sh; do
    [ -f "$repo/$input" ] && [ ! -L "$repo/$input" ] || fail "Use the complete trusted checkout; missing $input"
done
[ "$(id -u)" = 0 ] || fail 'Android root is required.'
[ "$(uname -m)" = aarch64 ] && [ -x /system/bin/sh ] || fail 'Requires Android ARM64.'
[ ! -e "$base" ] && [ ! -L "$base" ] || fail "Destination already exists: $base"
bb=${OMARCHY_BUSYBOX:-/data/adb/magisk/busybox}
[ -x "$bb" ] || fail 'Set OMARCHY_BUSYBOX to a root-accessible BusyBox with unshare, tar and chroot.'
for tool in tar chroot unshare mount; do "$bb" --list | grep -qx "$tool" || fail "BusyBox lacks $tool"; done
"$bb" unshare -m "$bb" mount --make-rprivate / || fail 'A private mount namespace is required.'
zstd=$(command -v zstd || true)
[ -n "$zstd" ] || fail 'Install zstd and add its bin directory to PATH.'
download_bytes=0
if [ -n "$image" ]; then
    [ -f "$image" ] || fail 'Offline image is missing.'
else
    command -v curl >/dev/null || fail 'Install curl or pass --image.'
    download_bytes=$size
fi
# Android mksh arithmetic can wrap at 2 GiB. Keep byte counts in awk,
# where these integer values are exactly representable as doubles.
need=$("$bb" awk -v unpacked="$unpacked" -v download="$download_bytes" 'BEGIN {printf "%.0f", unpacked + 536870912 + download}')
free=$("$bb" df -Pk /data | "$bb" awk 'END {printf "%.0f", $4 * 1024}')
"$bb" awk -v free="$free" -v need="$need" 'BEGIN {exit !(free + 0 >= need + 0)}' || fail "Need $need bytes on /data, have $free."
printf 'Image %s, free bytes %s, required %s\n' "$tag" "$free" "$need"
[ "$action" != dry-run ] || exit 0
mkdir -m 700 "$base" || fail 'Destination already exists.'
# From here on the directory belongs to this invocation. All mounts run in
# the separate launcher namespace; installer cleanup never crosses a mount.
cleanup() { status=$?; trap - EXIT; [ "$status" = 0 ] || rm -rf "$base"; exit "$status"; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
if [ -z "$image" ]; then
    image=$base/rootfs.tar.zst
    if ! curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 \
        --header 'Accept: application/octet-stream' --output "$image" "$api_url"; then
        curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 --output "$image" "$url"
    fi
fi
[ "$(wc -c < "$image" | tr -d '[:space:]')" = "$size" ] || fail 'Image length mismatch.'
actual=$("$bb" sha256sum "$image")
[ "${actual%% *}" = "$sha" ] || fail 'Image SHA-256 mismatch.'
mkdir "$base/rootfs"
# Separate FIFO gives both producer and tar an independently checked status.
fifo=$base/archive.fifo
"$bb" mkfifo "$fifo"
"$zstd" -dc "$image" >"$fifo" &
decoder=$!
"$bb" tar -xpf "$fifo" -C "$base/rootfs" || { kill "$decoder" 2>/dev/null || true; wait "$decoder" || true; fail 'Image extraction failed.'; }
wait "$decoder" || fail 'Image decompression failed.'
rm "$fifo"
[ "$image" != "$base/rootfs.tar.zst" ] || rm "$image"
# Only this freshly imported, hash-verified public rootfs is eligible for
# bootstrap. Existing guests were rejected before claiming the destination.
# Copy project commands from the same checkout as this installer, including
# the scope-aware session helpers and their menu, theme and panel callers.
for input in "$repo"/core/bin/* "$repo"/core/ai-suite/*launch; do
    [ -f "$input" ] && [ ! -L "$input" ] || continue
    IFS= read -r header < "$input" || true
    case "$header" in '#!'*) ;; *) continue;; esac
    target="$base/rootfs/usr/local/bin/${input##*/}"
    [ ! -L "$target" ] || fail "Unexpected symlink in public image: $target"
    cp "$input" "$target"
    chmod 755 "$target"
done
mkdir -p "$base/rootfs/usr/local/config"
cp "$repo/core/config/component-ownership.json" "$base/rootfs/usr/local/config/component-ownership.json"
chmod 644 "$base/rootfs/usr/local/config/component-ownership.json"
for script in start-omarchy.sh ubuntu-chroot.sh supervise.py; do
    cp "$here/$script" "$base/$script"
    chmod 700 "$base/$script"
done
printf '%s\n' "$bb" > "$base/busybox.path"
printf '%s %s\n' "$tag" "$sha" > "$base/image.receipt"
"$base/ubuntu-chroot.sh" /bin/bash -ec '/usr/local/bin/sway --version; /usr/local/bin/omarchy-playwright128 check'
echo "Installed $base/rootfs. Start termux-x11 :0 in Termux, then run su -c $base/start-omarchy.sh. For another display pass OMARCHY_X11_DISPLAY=:N inside su -c."
