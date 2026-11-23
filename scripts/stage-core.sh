#!/usr/bin/env bash
# Copy this checkout's shared desktop core into an existing, offline rootfs.
set -euo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
  echo "Usage: $0 ROOTFS_DIRECTORY (must already exist)" >&2
  exit 2
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
TARGET_ROOT="$(cd "$1" && pwd -P)"
if [[ "$TARGET_ROOT" == / ]]; then
  echo "Refusing to stage desktop files into the host root directory." >&2
  exit 2
fi

mkdir -p "$TARGET_ROOT/usr/local/bin" \
  "$TARGET_ROOT/root/.config/sway" \
  "$TARGET_ROOT/root/.config/fcitx5" \
  "$TARGET_ROOT/root/.config/omarchy"

stage_script() {
  local source="$1" first_line=""
  # Test/import caches and nested directories are not executable source inputs.
  [[ -f "$source" && ! -L "$source" ]] || return 0
  case "$source" in *.pyc|*.pyo) return 0 ;; esac
  IFS= read -r first_line < "$source" || true
  [[ "$first_line" == '#!'* ]] || return 0
  # Several tracked Python/shell sources are 0644; install their command mode here.
  install -m 755 "$source" "$TARGET_ROOT/usr/local/bin/${source##*/}"
}

for source in "$REPO_DIR"/core/bin/* "$REPO_DIR"/core/ai-suite/*launch; do
  stage_script "$source"
done
cp -f "$REPO_DIR/core/config/sway/config" "$TARGET_ROOT/root/.config/sway/config"
cp -f "$REPO_DIR/core/config/fcitx5/profile" "$TARGET_ROOT/root/.config/fcitx5/profile"

echo "Staged shared core from $REPO_DIR into $TARGET_ROOT"
