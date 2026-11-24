#!/usr/bin/env bash
# Builds a diagnostic executable. Does not install, push, or run it on a device.
set -euo pipefail
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 /absolute/path/to/procfd-probe [linker arguments ...]" >&2
  echo 'Set CC to a Linux compiler or an Android NDK aarch64-linux-android compiler.' >&2
  exit 2
fi
case "$1" in /*) ;; *) echo 'Output must be an absolute path.' >&2; exit 2 ;; esac
probe_output=$1
shift
probe_source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"${CC:-cc}" -std=gnu11 -O2 -Wall -Wextra -Werror \
  "$probe_source_dir/procfd-probe.c" -o "$probe_output" "$@"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$probe_source_dir/procfd-probe.c" "$probe_output"
else
  shasum -a 256 "$probe_source_dir/procfd-probe.c" "$probe_output"
fi
file "$probe_output"
