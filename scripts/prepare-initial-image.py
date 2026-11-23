#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Pack a clean, offline Ubuntu build for initial installation and describe its actual bytes."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

REPO = Path(__file__).resolve().parents[1]
UPSTREAM = "https://github.com/Audrey-cn/omarchy-android"
ARCHIVE = "rootfs.tar.zst"
DOC = "usr/share/doc/omarchy-image"
BROWSER_RECEIPT = "opt/omarchy-chromium128/source-manifest.json"
COMPONENT_MANIFESTS = {
    "desktop": ("usr/lib/omarchy-android/desktop/manifest.json", "source_commit"),
    "graphics": ("usr/share/doc/omarchy-graphics-runtime/manifest.json", "sourceSha"),
}


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def pack_archive(root, target):
    if root == root.parent or target.resolve().is_relative_to(root.resolve()):
        raise ValueError("Archive destination must be outside a dedicated rootfs directory")
    config = root / ".tiny.yaml"
    if config.is_symlink() or not config.is_file():
        raise ValueError("Missing top-level .tiny.yaml")
    # Give tar each top-level entry once. Paths do not need a ./ transform, which
    # could also rewrite symlink targets. Android creates its own runtime mounts.
    names = sorted(child.name for child in root.iterdir() if child.name != ".tiny.yaml")
    tar = shutil.which("gtar") or "tar"
    subprocess.run([tar, "--numeric-owner", "--one-file-system", "--hard-dereference",
                    "--sort=name", "--anchored", "--exclude=dev/*", "--exclude=proc/*", "--exclude=sys/*",
                    "--exclude=run/*", "--exclude=tmp/*", "-I", "zstd -T0 -9",
                    "-cf", str(target), "-C", str(root), "--", ".tiny.yaml", *names], check=True)


def unpacked_bytes(stream):
    total = 0
    with tarfile.open(fileobj=stream, mode="r|") as archive:
        for index, member in enumerate(archive):
            if index == 0 and (member.name != ".tiny.yaml" or not member.isfile()):
                raise ValueError("Archive must begin with .tiny.yaml")
            if member.isfile():
                total += member.size
            elif not (member.isdir() or member.issym()):
                raise ValueError("Unmaterialized hardlink or special entry: " + member.name)
    if total <= 0:
        raise ValueError("Empty rootfs archive")
    return total


def write_metadata(output, source_sha, unpacked, component_source_sha=None):
    component_source_sha = source_sha if component_source_sha is None else component_source_sha
    if (not re.fullmatch(r"[0-9a-f]{40}", source_sha)
            or not re.fullmatch(r"[0-9a-f]{40}", component_source_sha)
            or unpacked <= 0):
        raise ValueError("Invalid image source or unpacked size")
    tag = "desktop-ubuntu24-" + source_sha[:12]
    archive = output / ARCHIVE
    size = archive.stat().st_size
    if not 0 < size < 2 ** 31:
        raise ValueError("Archive must fit the GitHub Release per-asset size limit")
    checksum = sha256(archive)
    image = dict(id=tag, name="Ubuntu 24.04 · Omarchy " + source_sha[:12],
                 abi="arm64-v8a", minAppVersionCode=64, bytes=size, unpackedBytes=unpacked,
                 sha256=checksum, sourceSha=source_sha, componentSourceSha=component_source_sha,
                 urls=[f"{UPSTREAM}/releases/download/{tag}/{ARCHIVE}"])
    write_json(output / "image.json", image)
    write_json(output / "catalog.json", dict(schemaVersion=1, images=[image]))
    with (output / "SHA256SUMS").open("x") as sums:
        for path in sorted(output.iterdir()):
            if path.name != "SHA256SUMS":
                digest = checksum if path == archive else sha256(path)
                sums.write(f"{digest}  {path.name}\n")


def check_component_sources(root, checkout, component_source_sha):
    pinned_source = checkout if component_source_sha is None else component_source_sha
    if not re.fullmatch(r"[0-9a-f]{40}", pinned_source):
        raise ValueError("Invalid component source SHA")
    proof = root / BROWSER_RECEIPT
    browser = json.loads(proof.read_text())
    lock = REPO / "scripts/browser-bundle-sources.json"
    if browser["sourceSha"] != pinned_source or browser["sourceManifestSha256"] != sha256(lock):
        raise ValueError("Staged browser provenance differs from component source or source lock")
    if component_source_sha is not None:
        for kind, (name, key) in COMPONENT_MANIFESTS.items():
            manifest = root / name
            if manifest.is_symlink() or not manifest.is_file():
                raise ValueError(f"Missing staged {kind} component manifest")
            if json.loads(manifest.read_text()).get(key) != pinned_source:
                raise ValueError(f"Staged {kind} provenance differs from component source")
    return browser, pinned_source


def build(root, output, source_sha, component_source_sha=None):
    checkout = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "HEAD"], text=True).strip()
    if source_sha != checkout:
        raise ValueError("Image source SHA differs from checkout")
    if output.exists() or output.is_symlink() or root == root.parent or output.is_relative_to(root):
        raise ValueError("Use a new release directory outside the clean rootfs")
    browser, pinned_source = check_component_sources(root, checkout, component_source_sha)
    for name in ("usr/local/bin/sway", "usr/local/bin/omarchy-node-runtime",
                 "usr/local/bin/omarchy-playwright128", "root/.config/sway/config",
                 "opt/omarchy-node/node-v24.15.0-linux-arm64/bin/node",
                 "opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/chrome",
                 "opt/omarchy-chromium128/node_modules/playwright/package.json"):
        path = root / name
        if path.is_symlink():
            links = {"usr/local/bin/sway": "/opt/omarchy-graphics/bin/sway",
                     **{f"usr/local/bin/{cmd}": f"/usr/lib/omarchy-android/desktop/bin/{cmd}"
                        for cmd in ("omarchy-node-runtime", "omarchy-playwright128")}}
            if str(path.readlink()) != links.get(name):
                raise ValueError("Unexpected initial image launcher: " + name)
            path = root / links[name].lstrip("/")
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Incomplete initial image: " + name)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".initial-image-", dir=output.parent) as temp:
        staged = Path(temp) / "release"
        staged.mkdir()
        source = dict(repository=UPSTREAM, sourceSha=checkout, sourceUrl=f"{UPSTREAM}/tree/{checkout}",
                      componentSourceSha=pinned_source,
                      browserBundle=browser["bundleId"], browserVersions=browser["versions"],
                      browserSourceManifest="browser-sources.json", ubuntuPackages="packages.tsv",
                      upstreamComponents="upstream-components.tsv",
                      licenseFiles=[DOC, "usr/share/doc", "usr/share/common-licenses",
                                    "opt/omarchy-chromium128", "opt/omarchy-node"])
        write_json(staged / "source.json", source)
        shutil.copyfile(root / BROWSER_RECEIPT, staged / "browser-sources.json")
        for name in ("packages.tsv", "upstream-components.tsv", "LICENSES.md"):
            shutil.copyfile(root / DOC / name, staged / name)
        pack_archive(root, staged / ARCHIVE)
        with subprocess.Popen(["zstd", "-dc", str(staged / ARCHIVE)], stdout=subprocess.PIPE) as pipe:
            try:
                unpacked = unpacked_bytes(pipe.stdout)
                # Consume padding and verify the complete compressed frame.
                while pipe.stdout.read(1024 * 1024):
                    pass
            finally:
                pipe.stdout.close()
            if pipe.wait() != 0:
                raise ValueError("Cannot verify compressed archive")
        write_metadata(staged, checkout, unpacked, pinned_source)
        staged.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--component-source-sha",
                        help="Require all component receipts to match this commit (defaults to the assembly commit for the browser)")
    args = parser.parse_args()
    try:
        build(args.rootfs.resolve(), args.output_dir.absolute(), args.source_sha, args.component_source_sha)
    except (OSError, ValueError, KeyError, tarfile.TarError, subprocess.CalledProcessError) as error:
        print("Initial image preparation failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
