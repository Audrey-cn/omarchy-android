#!/usr/bin/env python3
"""Build a narrow Debian runtime unit on a disposable native Noble runner."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
DEPENDS = {
    "browser": "libc6 libstdc++6 libgcc-s1 libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 libdrm2 libdbus-1-3 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libx11-6 libxcb1 libxext6 libcairo2 libpango-1.0-0 libglib2.0-0t64",
    "graphics": "libc6 libgcc-s1 libffi8 libexpat1 libevdev2 libmtdev1t64 libudev1 libdrm2 libgbm1 libseat1 libpixman-1-0 libxkbcommon0 libxcb1 libxcb-composite0 libxcb-dri3-0 libxcb-ewmh2 libxcb-icccm4 libxcb-present0 libxcb-render-util0 libxcb-res0 libxcb-shm0 libxcb-xfixes0 libxcb-xinput0 libjson-c5 libpcre2-8-0 libcairo2 libpango-1.0-0 libpangocairo-1.0-0 libgdk-pixbuf-2.0-0",
}


def digest(file):
    value = hashlib.sha256()
    with file.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            value.update(block)
    return value.hexdigest()


def build(kind, payload, output, version):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}", version):
        raise ValueError("Expected a release version like 1.0.0")
    package = f"omarchy-{kind}-runtime"
    prefix = "usr/share/doc/" + package + "/"
    allowed = (prefix, "opt/omarchy-chromium128/", "opt/omarchy-node/") if kind == "browser" else (prefix, "opt/omarchy-graphics/")
    lock = HERE / ("browser-bundle-sources.json" if kind == "browser" else "graphics-sources.json")
    sources = json.loads(lock.read_text())
    git = ["git", "-c", "safe.directory=" + str(HERE.parent), "-C", HERE.parent]
    provenance_paths = ["scripts/runtime-component.py", "scripts/" + lock.name,
                        f"scripts/{kind}-image-e550-baseline.json.xz",
                        "scripts/build-graphics-runtime.sh" if kind == "graphics" else "scripts/build-browser-bundle.py"]
    if subprocess.check_output([*git, "status", "--porcelain", "--", *provenance_paths]).strip():
        raise ValueError("Runtime build inputs must belong to the source commit")
    sha = subprocess.check_output([*git, "rev-parse", "HEAD"], text=True).strip()
    if kind == "browser":
        receipt = json.loads((payload / "opt/omarchy-chromium128/source-manifest.json").read_text())
        if (receipt["sourceSha"], receipt["sourceManifestSha256"]) != (sha, digest(lock)):
            raise ValueError("Browser build receipt does not match source checkout")
    output.mkdir(parents=True, exist_ok=True)
    artifact = f"{package}_{version}_arm64"
    with tempfile.TemporaryDirectory(dir=output) as tmp:
        work = Path(tmp)
        pkg = work / "pkg"
        shutil.copytree(payload, pkg, symlinks=True)
        doc = pkg / prefix
        doc.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(lock, doc / "sources.json")
        shutil.copyfile(HERE / f"{kind}-image-e550-baseline.json.xz", doc / "legacy.json.xz")
        shutil.copyfile(HERE.parent / "LICENSES.md", doc / "copyright")
        entries = {}
        for path in sorted(pkg.rglob("*")):
            if path.is_dir() and not path.is_symlink():
                continue
            name = path.relative_to(pkg).as_posix()
            if not name.startswith(allowed) or not (path.is_file() or path.is_symlink()):
                raise ValueError("Unexpected payload: " + name)
            entries[name] = "link:" + os.readlink(path) if path.is_symlink() else digest(path)
        if not entries.get("opt/omarchy-graphics/bin/sway" if kind == "graphics" else "opt/omarchy-chromium128/source-manifest.json"):
            raise ValueError("Missing core runtime executable or receipt")
        metadata = dict(package=package, version=version, architecture="arm64", sourceSha=sha,
                        os="ubuntu", osVersion="24.04", runtimeApi=1, minAppVersionCode=64,
                        depends=DEPENDS[kind].split(), sources=sources["sources"])
        (doc / "manifest.json").write_text(json.dumps(dict(metadata, files=entries), sort_keys=True) + "\n")
        control = pkg / "DEBIAN"
        control.mkdir()
        (control / "control").write_text(f"Package: {package}\nVersion: {version}\nArchitecture: arm64\n"
            "Maintainer: Omarchy Android maintainers\nSection: misc\nPriority: optional\n"
            f"Depends: {', '.join(metadata['depends'])}\nDescription: Omarchy Android {kind} runtime\n")
        deb = work / (artifact + ".deb")
        subprocess.run(["dpkg-deb", "--root-owner-group", "-Zzstd", "--build", pkg, deb], check=True)
        metadata.update(asset=deb.name, bytes=deb.stat().st_size, sha256=digest(deb))
        sidecar = work / (artifact + ".json")
        sidecar.write_text(json.dumps(metadata, indent=2) + "\n")
        deb.replace(output / deb.name)
        sidecar.replace(output / sidecar.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=DEPENDS)
    parser.add_argument("payload", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    build(args.kind, args.payload, args.output, args.version)
