#!/usr/bin/env bash
# Ubuntu x86_64 -> Android ARM64. Produces artifacts only; no device operations.
set -euo pipefail
umask 077
if [[ $# -ne 2 || "$1" != /* || "$2" != /* ]]; then
  echo "Usage: bash $0 /absolute/scratch-directory /absolute/artifact-directory" >&2
  exit 2
fi
experiment_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source "$experiment_dir/sources.env"
work_dir=$1
artifact_dir=$2
if [[ -e "$work_dir" ]]; then
  echo 'Scratch directory must not already exist; refusing to reuse build state.' >&2
  exit 2
fi
mkdir -p "$work_dir" "$artifact_dir"
exec > >(tee "$artifact_dir/build.log") 2>&1
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]]
for program in curl sha256sum sha1sum tar unzip patch make python3 cc readelf; do
  command -v "$program" >/dev/null
done
download() {
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "$1" --output "$2"
}
download "$TINY_URL" "$work_dir/tiny.tar.gz"
download "$TALLOC_URL" "$work_dir/talloc.tar.gz"
download "$NDK_URL" "$work_dir/$NDK_ARCHIVE"
printf '%s  %s\n' "$TINY_SHA256" "$work_dir/tiny.tar.gz" | sha256sum --check
printf '%s  %s\n' "$TALLOC_SHA256" "$work_dir/talloc.tar.gz" | sha256sum --check
printf '%s  %s\n' "$PATCH_SHA256" "$experiment_dir/a179d3e8.patch" | sha256sum --check
printf '%s  %s\n' "$NDK_SHA1" "$work_dir/$NDK_ARCHIVE" | sha1sum --check
sha256sum "$work_dir/tiny.tar.gz" "$work_dir/talloc.tar.gz" \
  "$work_dir/$NDK_ARCHIVE" > "$artifact_dir/download-sha256.txt"
unzip -q "$work_dir/$NDK_ARCHIVE" -d "$work_dir/ndk"
ndk_dir="$work_dir/ndk/android-ndk-r27d"
grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$NDK_VERSION$" "$ndk_dir/source.properties"
cp "$ndk_dir/source.properties" "$artifact_dir/ndk-source.properties"
ndk_bin="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
export OMARCHY_PROOT_NDK_BIN="$ndk_bin"
export OMARCHY_PROOT_ANDROID_API="$ANDROID_API"
export PATH="$ndk_bin:$PATH"
export CC="$ndk_bin/aarch64-linux-android${ANDROID_API}-clang"
export AR="$ndk_bin/llvm-ar"
export RANLIB="$ndk_bin/llvm-ranlib"
export STRIP="$ndk_bin/llvm-strip"
export CFLAGS='-O2 -fPIC'
export LDFLAGS=''
export PYTHON=python3
unset PROOT_UNBUNDLE_LOADER PROOT_WITH_LIBANDROID_SHMEM
"$CC" --version > "$artifact_dir/compiler-version.txt"
sha256sum "$experiment_dir/build-android.sh" "$experiment_dir/build-probe.sh" \
  "$experiment_dir/procfd-probe.c" "$experiment_dir/talloc-cross-answers.txt" \
  "$experiment_dir/sources.env" "$experiment_dir/build-compat.patch" \
  > "$artifact_dir/recipe-sha256.txt"

# A native Linux run validates the actual probe's write/mmap checks, not Android.
env -u CC -u CFLAGS -u LDFLAGS /usr/bin/cc -std=gnu11 -O2 -Wall -Wextra -Werror \
  "$experiment_dir/procfd-probe.c" -o "$work_dir/probe-linux"
"$work_dir/probe-linux" > "$artifact_dir/probe-linux-control.txt"
bash "$experiment_dir/build-probe.sh" "$artifact_dir/procfd-probe-android-arm64"

mkdir "$work_dir/talloc" "$work_dir/proot-control"
tar -xzf "$work_dir/talloc.tar.gz" -C "$work_dir/talloc" --strip-components=1
tar -xzf "$work_dir/tiny.tar.gz" -C "$work_dir/proot-control" --strip-components=1
# Identical declaration-only fix in both variants; keep the a179 comparison isolated.
(
  cd "$work_dir/proot-control"
  patch --dry-run --fuzz=0 -p1 < "$experiment_dir/build-compat.patch"
  patch --fuzz=0 -p1 < "$experiment_dir/build-compat.patch"
)
cp -a "$work_dir/proot-control" "$work_dir/proot-patched"
(
  cd "$work_dir/proot-patched"
  patch --dry-run --fuzz=0 -p1 < "$experiment_dir/a179d3e8.patch"
  patch --fuzz=0 -p1 < "$experiment_dir/a179d3e8.patch"
)
# Assert that Tiny-specific code remains in both variants.
for variant in control patched; do
  grep -q -- '--tiny-storage' "$work_dir/proot-$variant/src/cli/proot.c"
done

# Cross answers and static archive recipe follow Termux's pinned libtalloc recipe.
prefix="$work_dir/talloc-prefix"
mkdir -p "$prefix/include" "$prefix/lib"
cp "$experiment_dir/talloc-cross-answers.txt" "$work_dir/talloc/cross-answers.txt"
(
  cd "$work_dir/talloc"
  ./configure --prefix="$prefix" --disable-rpath --disable-python \
    --without-gettext --cross-compile --cross-answers=cross-answers.txt
  make -j2
  mapfile -t talloc_objects < <(find bin/default -maxdepth 1 -name 'talloc*.o' -type f | sort)
  [[ ${#talloc_objects[@]} -gt 0 ]]
  "$AR" rcs "$prefix/lib/libtalloc.a" "${talloc_objects[@]}"
  cp talloc.h "$prefix/include/talloc.h"
)

# GNUmakefile uses -m32 to build its ARM loader. NDK requires a separate driver.
cat > "$work_dir/ndk-cc" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
compiler="aarch64-linux-android${OMARCHY_PROOT_ANDROID_API}-clang"
arguments=()
for argument in "$@"; do
  if [[ "$argument" == -m32 ]]; then
    compiler="armv7a-linux-androideabi${OMARCHY_PROOT_ANDROID_API}-clang"
  else
    arguments+=("$argument")
  fi
done
exec "$OMARCHY_PROOT_NDK_BIN/$compiler" "${arguments[@]}"
WRAPPER
chmod 700 "$work_dir/ndk-cc"
for variant in control patched; do
  variant_dir="$work_dir/proot-$variant"
  mkdir -p "$artifact_dir/$variant"
  env CPPFLAGS="-I$prefix/include -DARG_MAX=131072" \
    CFLAGS='-O2 -fPIC -pthread' \
    LDFLAGS="-L$prefix/lib -pthread" \
    make -C "$variant_dir/src" -j2 V=1 \
      CC="$work_dir/ndk-cc" STRIP="$STRIP" \
      OBJCOPY="$ndk_bin/llvm-objcopy" OBJDUMP="$ndk_bin/llvm-objdump" \
      LOADER_CFLAGS='-fPIC -ffreestanding -fno-stack-protector -fno-builtin' \
      'LOADER_CFLAGS-m32=-fPIC -ffreestanding -fno-stack-protector -fno-builtin'
  cp "$variant_dir/src/proot" "$artifact_dir/$variant/proot"
  cp "$variant_dir/src/loader/loader" "$artifact_dir/$variant/loader-aarch64"
  cp "$variant_dir/src/loader/loader-m32" "$artifact_dir/$variant/loader-arm"
  cp "$variant_dir/src/build.h" "$artifact_dir/$variant/build.h"
  "$ndk_bin/llvm-readelf" --file-header --program-headers --dynamic \
    "$artifact_dir/$variant/proot" > "$artifact_dir/$variant/proot-readelf.txt"
  grep -q 'AArch64' "$artifact_dir/$variant/proot-readelf.txt"
  if grep -q 'Shared library:.*libtalloc' "$artifact_dir/$variant/proot-readelf.txt"; then
    echo 'Unexpected dynamic libtalloc dependency.' >&2
    exit 1
  fi
done
cmp "$artifact_dir/control/loader-aarch64" "$artifact_dir/patched/loader-aarch64"
cmp "$artifact_dir/control/loader-arm" "$artifact_dir/patched/loader-arm"
cp "$experiment_dir/a179d3e8.patch" "$experiment_dir/build-compat.patch" \
  "$experiment_dir/sources.env" \
  "$experiment_dir/procfd-probe.c" "$experiment_dir/build-android.sh" \
  "$experiment_dir/build-probe.sh" "$experiment_dir/talloc-cross-answers.txt" \
  "$experiment_dir/README.md" "$artifact_dir/"
cp "$work_dir/proot-patched/COPYING" "$artifact_dir/PROOT-COPYING"
cp "$work_dir/talloc/LICENSE" "$artifact_dir/TALLOC-LICENSE"
diff -u "$work_dir/proot-control/src/extension/fake_id0/fake_id0.c" \
  "$work_dir/proot-patched/src/extension/fake_id0/fake_id0.c" \
  > "$artifact_dir/applied-source.diff" || [[ $? == 1 ]]
export TINY_SHA UPSTREAM_SHA TALLOC_VERSION NDK_VERSION ANDROID_API
export OMARCHY_PROOT_ARTIFACT_DIR="$artifact_dir"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
receipt = {key: os.environ[key] for key in
           ['TINY_SHA', 'UPSTREAM_SHA', 'TALLOC_VERSION', 'NDK_VERSION', 'ANDROID_API']}
receipt['repository_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
receipt['github_run_id'] = os.environ.get('GITHUB_RUN_ID')
receipt['status'] = 'cross_compiled_not_device_validated'
receipt['build_compatibility_patch_sha256'] = hashlib.sha256(pathlib.Path(
    os.environ['OMARCHY_PROOT_ARTIFACT_DIR'], 'build-compat.patch').read_bytes()).hexdigest()
pathlib.Path(os.environ['OMARCHY_PROOT_ARTIFACT_DIR'], 'receipt.json').write_text(
    json.dumps(receipt, indent=2) + '\n')
PY
(
  cd "$artifact_dir"
  find . -type f ! -name SHA256SUMS ! -name build.log -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
