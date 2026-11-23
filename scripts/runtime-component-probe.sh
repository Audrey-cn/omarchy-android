#!/usr/bin/env bash
# Verify dynamically resolved machine code on the same native Noble build runner.
set -euo pipefail
[[ $# == 2 && -d "$1" && $EUID == 0 ]]
out=$(cd "$1" && pwd -P)
kind=$2
case "$kind" in
  browser) programs=(/opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/chrome /opt/omarchy-node/node-v24.15.0-linux-arm64/bin/node);;
  graphics) programs=(/opt/omarchy-graphics/bin/{sway,swaymsg,swaybar,swaynag,libinput,wayland-scanner});;
  *) exit 2;;
esac
debs=("$out"/omarchy-"$kind"-runtime_*_arm64.deb)
[[ ${#debs[@]} == 1 && -f ${debs[0]} ]]
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends "${debs[0]}"
python3 - "$out" "$kind" "${programs[@]}" <<'PY'
import json, os, re, subprocess, sys
from pathlib import Path
output, kind, *programs = sys.argv[1:]
links = {}
packages = {}
for program in programs:
    linked = subprocess.check_output(["ldd", program], text=True)
    if "not found" in linked:
        raise SystemExit(linked)
    files = re.findall(r"^\s*(?:[^\s]+ => )?(/\S+) \(", linked, re.M)
    if not files:
        raise SystemExit("No ELF dependencies found: " + program)
    links[program] = files
    for file in files:
        if file.startswith("/opt/omarchy-"):
            continue
        real = os.path.realpath(file)
        for path in dict.fromkeys([file, real, real.removeprefix("/usr")]):
            proc = subprocess.run(["dpkg-query", "-S", path], capture_output=True, text=True)
            if proc.returncode == 0:
                owner = proc.stdout.splitlines()[0].split(": ", 1)[0]
                packages[owner] = subprocess.check_output(["dpkg-query", "-W", "-f=${Version}", owner], text=True)
                break
        else:
            raise SystemExit("Shared library owner is unknown: " + file)
if kind == "graphics":
    sway = links[programs[0]]
    for prefix in ("libwlroots", "libwayland-server"):
        if not any(path.startswith("/opt/omarchy-graphics/") and Path(path).name.startswith(prefix) for path in sway):
            raise SystemExit("Sway did not load the private " + prefix)
    subprocess.run([programs[0], "--version"], check=True)
else:
    for binary, version in zip(programs, ("128.0.6613.18", "v24.15.0")):
        result = subprocess.check_output([binary, "--version"], text=True)
        if version not in result:
            raise SystemExit("Incorrect runtime version: " + result)
evidence = Path(output) / (kind + "-linkage.json")
evidence.write_text(json.dumps({"programs":links, "ubuntuPackages":packages}, indent=2, sort_keys=True) + "\n")
PY
