#!/usr/bin/env bash
# Run in a disposable native Ubuntu Noble ARM64 build environment, as root.
set -euo pipefail
[[ $# == 2 && $1 == /* ]] || { echo "Usage: $0 /absolute/output VERSION" >&2; exit 2; }
repo=$(cd "$(dirname "$0")" && pwd -P)
out=$1
version=$2
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 && $(dpkg --print-architecture) == arm64 && $EUID == 0 ]]
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends python3-venv python3-pip curl ca-certificates jq xz-utils \
  build-essential ninja-build pkg-config scdoc libexpat1-dev libffi-dev libevdev-dev libmtdev-dev \
  libwayland-dev wayland-protocols libpixman-1-dev libxkbcommon-dev libdrm-dev libinput-dev libudev-dev \
  libseat-dev libgbm-dev libxcb1-dev libxcb-composite0-dev libxcb-render-util0-dev libxcb-xfixes0-dev \
  libxcb-icccm4-dev libxcb-xinput-dev libxcb-shm0-dev libxcb-present-dev libxcb-dri3-dev libxcb-res0-dev \
  libxcb-ewmh-dev libjson-c-dev libpcre2-dev libcairo2-dev libpango1.0-dev libgdk-pixbuf-2.0-dev
python3 -m venv "$work/venv"
echo 'meson==1.7.2 --hash=sha256:82c6818dc81743c96de3a458f06175776ebfde4081195ea31ea6971838f25e38' > "$work/requirements"
"$work/venv/bin/pip" install --require-hashes --no-deps -r "$work/requirements"
prefix=/opt/omarchy-graphics
export PATH="$work/venv/bin:$prefix/bin:$PATH"
export LD_LIBRARY_PATH="$prefix/lib/aarch64-linux-gnu:$prefix/lib"
# Propagate the private search path into each ELF, including transitive libraries.
export LDFLAGS="-Wl,-rpath,$prefix/lib/aarch64-linux-gnu -Wl,-rpath,$prefix/lib"
export PKG_CONFIG_PATH="$prefix/lib/aarch64-linux-gnu/pkgconfig:$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git -c safe.directory="$(dirname "$repo")" -C "$repo" log -1 --format=%ct)}
mkdir "$work/payload"
while IFS=$'\t' read -r id version_upstream url archive digest; do
  cd "$work"
  curl -fLsS --proto '=https' --retry 3 -o "$archive" "$url"
  echo "$digest  $archive" | sha256sum -c -
  tar -xf "$archive"
  cd "$id-$version_upstream"
  case "$id" in
    wayland) options=(-Ddocumentation=false -Dtests=false -Ddtd_validation=false);;
    wayland-protocols) options=(-Dtests=false);;
    libinput) options=(-Dlibwacom=false -Ddebug-gui=false -Dtests=false -Ddocumentation=false -Dzshcompletiondir=no -Dudev-dir="$prefix/lib/udev");;
    wlroots)
      test "$(grep -cF 'x11->have_dri3 = true;' backend/x11/backend.c)" = 1
      sed -i 's/x11->have_dri3 = true;/x11->have_dri3 = false;/' backend/x11/backend.c
      options=(-Dbackends=x11 -Drenderers= -Dallocators= -Dxwayland=disabled -Dexamples=false);;
    sway) options=(-Dman-pages=disabled -Dtray=disabled);;
    *) exit 1;;
  esac
  meson setup build --prefix="$prefix" --buildtype=release --wrap-mode=nodownload "${options[@]}"
  ninja -C build
  # Host install is required for the next component's pkg-config lookups.
  ninja -C build install
  DESTDIR="$work/payload" ninja -C build install
  doc="$work/payload/usr/share/doc/omarchy-graphics-runtime/$id"
  mkdir -p "$doc"
  find . -maxdepth 1 -type f \( -iname 'license*' -o -iname 'copying*' \) -exec cp -t "$doc" {} +
done < <(jq -r '.sources[] | [.id,.version,.url,.archive,.sha256] | @tsv' "$repo/graphics-sources.json")
env -u LD_LIBRARY_PATH "$prefix/bin/sway" --version
python3 "$repo/runtime-component.py" graphics "$work/payload" "$out" "$version"
