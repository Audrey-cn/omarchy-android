#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build an explicit Chromium 128 candidate as verified Android managed assets.

Downloads are data only: this builder never runs Node, Chromium, npm or archive
install hooks. It does not edit rootfs, Android defaults or workflow switches.
"""

import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile


REPO = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name("browser-bundle-sources.json")
PREFIXES = ("opt/omarchy-chromium128/", "opt/omarchy-node/")


def digest(path, algorithm="sha256"):
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def safe_name(name):
    if not name or "\\" in name or name.startswith("/"):
        raise ValueError("Archive path must be relative: " + name)
    path = PurePosixPath(name)
    if ".." in path.parts or not path.parts or ":" in path.parts[0]:
        raise ValueError("Unsafe archive path: " + name)
    return str(path)


def read_lock(path):
    value = json.loads(path.read_text())
    if value.get("schemaVersion") != 1 or value.get("activation") != "explicit-candidate-only":
        raise ValueError("Unknown browser source manifest schema or activation policy")
    if len(value["sources"]) != 4 or set(source["id"] for source in value["sources"]) != {"chromium", "playwright", "playwright-core", "node"}:
        raise ValueError("A bundle requires the complete Chromium/Playwright/Node source set")
    for source in value["sources"]:
        if not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
            raise ValueError("Invalid pinned source SHA256")
        if safe_name(source["archive"]) != Path(source["archive"]).name:
            raise ValueError("Cache archive names must be basenames")
        destination = safe_name(source["destination"]).rstrip("/") + "/"
        if not destination.startswith(PREFIXES):
            raise ValueError("Source destination is outside the managed browser prefixes")
        if source["format"] not in {"zip", "tar"} or not source["urls"]:
            raise ValueError("Unsupported archive format or empty source URLs")
        if any(not url.startswith("https://") for url in source["urls"]):
            raise ValueError("Official source downloads require HTTPS")
    return value


def verified(path, source):
    if path.is_symlink() or not path.is_file():
        return False
    if source.get("bytes") is not None and path.stat().st_size != source["bytes"]:
        return False
    if digest(path) != source["sha256"]:
        return False
    if source.get("npmIntegrity"):
        algorithm, encoded = source["npmIntegrity"].split("-", 1)
        if algorithm != "sha512" or base64.b64encode(bytes.fromhex(digest(path, "sha512"))).decode() != encoded:
            return False
    return True


def cached_archive(source, cache, override=None, offline=False):
    if cache.is_symlink():
        raise ValueError("Cache directory must not be a symlink")
    cache.mkdir(parents=True, exist_ok=True)
    target_dir = cache / source["sha256"]
    if target_dir.is_symlink():
        raise ValueError("Cache source directory must not be a symlink")
    target_dir.mkdir(exist_ok=True)
    target = target_dir / source["archive"]
    with (cache / (source["sha256"] + ".lock")).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if verified(target, source):
            return target
        if target.exists() or target.is_symlink():
            if not target.is_file() or target.is_symlink():
                raise ValueError("Cache entry is not a regular file: " + str(target))
            print("Discarding corrupt cached archive: " + source["id"], file=sys.stderr)
            target.unlink()
        if offline and override is None:
            raise ValueError("Verified archive is absent from offline cache: " + source["id"])
        with tempfile.TemporaryDirectory(prefix=".fetch-", dir=target_dir) as temp:
            candidate = Path(temp) / source["archive"]
            if override is not None:
                if override.is_symlink() or not override.is_file():
                    raise ValueError("Archive override must be a regular file")
                shutil.copyfile(override, candidate)
                if not verified(candidate, source):
                    raise ValueError("Archive override failed pinned hash/integrity: " + source["id"])
            else:
                success = False
                for url in source["urls"]:
                    try:
                        print("Fetching pinned source: " + source["id"], file=sys.stderr)
                        with urllib.request.urlopen(url, timeout=60) as response, candidate.open("wb") as output:
                            total = 0
                            while True:
                                block = response.read(1024 * 1024)
                                if not block:
                                    break
                                total += len(block)
                                if source.get("bytes") is not None and total > source["bytes"]:
                                    raise ValueError("Download exceeds pinned source length")
                                output.write(block)
                            output.flush()
                            os.fsync(output.fileno())
                        if not verified(candidate, source):
                            raise ValueError("Downloaded archive failed pinned hash/integrity")
                        success = True
                        break
                    except (OSError, ValueError, urllib.error.URLError):
                        candidate.unlink(missing_ok=True)
                if not success:
                    raise ValueError("No official URL produced the pinned archive: " + source["id"])
            candidate.chmod(0o644)
            os.replace(candidate, target)
        return target


def extract(source, archive_path, payload):
    """Extract regular bytes, resolving contained links without creating symlinks."""
    prefix = safe_name(source["stripPrefix"]).rstrip("/")
    destination = payload / source["destination"]
    transformed = []
    with (zipfile.ZipFile(archive_path) if source["format"] == "zip" else tarfile.open(archive_path)) as archive:
        entries = {}
        members = archive.infolist() if source["format"] == "zip" else archive.getmembers()
        for member in members:
            name = safe_name(member.filename if source["format"] == "zip" else member.name)
            directory = member.is_dir() if source["format"] == "zip" else member.isdir()
            if name == prefix and directory:
                continue
            if not name.startswith(prefix + "/"):
                raise ValueError("Archive contains files outside its pinned root: " + name)
            if directory:
                continue
            if name in entries:
                raise ValueError("Duplicate archive member: " + name)
            if source["format"] == "zip":
                mode = member.external_attr >> 16
                kind = stat.S_IFMT(mode)
                if kind not in {0, stat.S_IFREG, stat.S_IFLNK}:
                    raise ValueError("Unsupported ZIP member type: " + name)
            else:
                mode = member.mode
                if not (member.isfile() or member.issym() or member.islnk()):
                    raise ValueError("Unsupported TAR member type: " + name)
            entries[name] = (member, mode)

        def original_bytes(name, visiting=()):
            if name not in entries or name in visiting or len(visiting) > 20:
                raise ValueError("Archive link is missing, cyclic or escapes the source root: " + name)
            member, mode = entries[name]
            is_link = stat.S_ISLNK(mode) if source["format"] == "zip" else member.issym() or member.islnk()
            if is_link:
                link = archive.read(member).decode() if source["format"] == "zip" else member.linkname
                if link.startswith("/") or "\\" in link:
                    raise ValueError("Absolute or invalid archive link")
                if source["format"] == "tar" and member.islnk():
                    target = safe_name(link)
                else:
                    target = safe_name(posixpath.normpath(posixpath.join(posixpath.dirname(name), link)))
                return original_bytes(target, visiting + (name,))
            stream = archive.open(member) if source["format"] == "zip" else archive.extractfile(member)
            return stream, mode

        # Keep TAR member order: sorting a compressed TAR forces repeated
        # backwards seeks and decompression of the complete Node distribution.
        for name in entries:
            relative = name[len(prefix) + 1:]
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise ValueError("Overlapping bundle destination: " + str(target))
            stream, mode = original_bytes(name)
            with stream, target.open("xb") as output:
                shutil.copyfileobj(stream, output)
            target.chmod(0o755 if mode & 0o111 else 0o644)
            member, original_mode = entries[name]
            linked = stat.S_ISLNK(original_mode) if source["format"] == "zip" else member.issym() or member.islnk()
            if linked:
                transformed.append({"path": str(target.relative_to(payload)), "operation": "materialized-contained-link"})

    # npm's entrypoint uses paths relative to its real location. A byte copy of
    # bin/npm's symlink target would break those imports, so use explicit shims.
    if source["id"] == "node":
        for name, script in {"npm": "npm/bin/npm-cli.js", "npx": "npm/bin/npx-cli.js",
                             "corepack": "corepack/dist/corepack.js"}.items():
            entry = destination / "lib/node_modules" / script
            if not entry.is_file():
                raise ValueError("Official Node distribution is missing " + name)
            launcher = destination / "bin" / name
            launcher.write_text('#!/bin/sh\nexec "$(dirname "$0")/node" "$(dirname "$0")/../lib/node_modules/' + script + '" "$@"\n')
            launcher.chmod(0o755)
            transformed.append({"path": str(launcher.relative_to(payload)), "operation": "contained-node-npm-shim"})
    return transformed


def check_payload(payload, versions):
    root = payload / "opt/omarchy-chromium128"
    for name in ("playwright", "playwright-core"):
        package = json.loads((root / "node_modules" / name / "package.json").read_text())
        if package["name"] != name or package["version"] != versions["playwright"]:
            raise ValueError("Playwright package/version differs from the locked pair")
        if name == "playwright" and package["dependencies"]["playwright-core"] != versions["playwright"]:
            raise ValueError("Playwright core dependency is not the locked exact version")
    browsers = json.loads((root / "node_modules/playwright-core/browsers.json").read_text())["browsers"]
    chromium = next(browser for browser in browsers if browser["name"] == "chromium")
    if chromium["revision"] != versions["chromiumRevision"] or chromium["browserVersion"] != versions["chromium"]:
        raise ValueError("Playwright browser registry does not match the locked Chromium")
    for executable in (root / ("browsers/chromium-" + versions["chromiumRevision"] + "/chrome-linux/chrome"),
                       payload / ("opt/omarchy-node/node-v" + versions["node"] + "-linux-arm64/bin/node")):
        with executable.open("rb") as stream:
            header = stream.read(20)
        if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
            raise ValueError("Pinned runtime is not Linux ELF64 AArch64: " + str(executable))
        if not executable.stat().st_mode & 0o111:
            raise ValueError("Pinned runtime lost its executable mode")


def build(output, cache, lock_path=LOCK, overrides=None, offline=False, repo=REPO):
    lock = read_lock(lock_path)
    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Managed assets require the current checkout SHA")
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; choose a new candidate directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    archives = [(source, cached_archive(source, cache, (overrides or {}).get(source["id"]), offline))
                for source in lock["sources"]]
    with tempfile.TemporaryDirectory(prefix=".browser-bundle-", dir=output.parent) as temp:
        staged = Path(temp) / "candidate"
        payload = staged / "managed-core/files"
        payload.mkdir(parents=True)
        transforms = []
        for source, archive in archives:
            transforms.extend(extract(source, archive, payload))
        check_payload(payload, lock["versions"])
        provenance = dict(lock, sourceSha=source_sha, sourceManifestSha256=digest(lock_path),
                          transformations=transforms)
        receipt = payload / "opt/omarchy-chromium128/source-manifest.json"
        receipt.write_text(json.dumps(provenance, sort_keys=True, indent=2) + "\n")
        receipt.chmod(0o644)
        files = []
        for path in sorted(payload.rglob("*")):
            if path.is_symlink() or not (path.is_dir() or path.is_file()):
                raise ValueError("Managed payload contains a non-regular entry")
            if path.is_file():
                files.append({"path": path.relative_to(payload).as_posix(), "sha256": digest(path),
                              "mode": 493 if path.stat().st_mode & 0o111 else 420})
        manifest = {"schemaVersion": 1, "sourceSha": source_sha, "files": files}
        (staged / "managed-core/manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
        os.replace(staged, output)
    print("Built explicit browser candidate: " + str(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/omarchy-browser-sources")
    parser.add_argument("--sources", type=Path, default=LOCK)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--archive", action="append", default=[], metavar="ID=PATH", help="Seed the cache with an existing archive; pinned hashes still apply")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        overrides = {}
        for value in args.archive:
            name, path = value.split("=", 1)
            if name in overrides or name not in {"chromium", "playwright", "playwright-core", "node"}:
                raise ValueError("Duplicate or unknown source override")
            overrides[name] = Path(path)
        build(args.output_dir.absolute(), args.cache_dir.absolute(), args.sources, overrides,
              args.offline, args.repo)
    except (OSError, ValueError, KeyError, StopIteration, tarfile.TarError, zipfile.BadZipFile,
            subprocess.CalledProcessError) as error:
        print("Browser candidate build failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
