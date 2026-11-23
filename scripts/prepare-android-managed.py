#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the small, hash-checked managed-file asset tree independently of rootfs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


MARKER = ".omarchy-managed-assets"
MARKER_TEXT = "omarchy-managed-assets-v1\n"


def validate(entry):
    path = entry["path"]
    parts = path.split("/")
    if any(part in {"", ".", ".."} for part in parts) or any(ord(c) < 32 or ord(c) == 127 or c == "\\" for c in path):
        raise ValueError("Invalid managed path")
    if not ((len(parts) == 4 and parts[:3] == ["usr", "local", "bin"]) or
            (len(parts) >= 3 and parts[0] == "opt" and parts[1] in {"omarchy-chromium128", "omarchy-node"})):
        raise ValueError("Unapproved managed path: " + path)
    if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
        raise ValueError("Invalid managed SHA-256")
    if type(entry["mode"]) is not int or entry["mode"] not in {420, 493}:
        raise ValueError("Managed file mode must be 0644 or 0755")


def checked_source(directory, relative):
    source = directory
    for part in relative.split("/"):
        source = source / part
        if source.is_symlink():
            raise ValueError("Managed input must not contain symlinks")
    if not source.is_file():
        raise ValueError("Managed input is not a regular file")
    return source


def copy(source, target, mode):
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    with source.open("rb") as incoming, target.open("xb") as outgoing:
        while True:
            chunk = incoming.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            outgoing.write(chunk)
    target.chmod(mode)
    return digest.hexdigest()


def build(repo, output, extra, source_sha=None):
    if source_sha is None:
        source_sha = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Invalid source SHA")
    if output.is_symlink() or output == output.parent:
        raise ValueError("Invalid generated asset output directory")
    if output.exists():
        marker = output / MARKER
        if marker.is_symlink() or not marker.is_file() or marker.read_text() != MARKER_TEXT:
            raise ValueError("Refusing to remove output without the managed-assets generator marker")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".managed-assets-", dir=output.parent))
    try:
        files = {}
        sources = sorted((repo / "core/bin").iterdir()) + sorted((repo / "core/ai-suite").glob("*launch"))
        for source in sources:
            if source.is_symlink() or not source.is_file() or source.suffix in {".pyc", ".pyo"}:
                continue
            with source.open("rb") as stream:
                if stream.read(2) != b"#!":
                    continue
            path = "usr/local/bin/" + source.name
            if path in files:
                raise ValueError("Duplicate core command: " + path)
            sha256 = copy(source, staging / "managed-core/files" / path, 0o755)
            files[path] = dict(path=path, sha256=sha256, mode=493)
        if extra:
            if extra.is_symlink() or not extra.is_dir():
                raise ValueError("Extra managed asset directory must be a real directory")
            manifest_source = checked_source(extra, "managed-core/manifest.json")
            manifest = json.loads(manifest_source.read_text())
            if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1 or manifest["sourceSha"] != source_sha:
                raise ValueError("Extra managed manifest schema/source SHA differs from this checkout")
            for entry in manifest["files"]:
                validate(entry)
                path = entry["path"]
                if path in files:
                    raise ValueError("Duplicate managed file: " + path)
                source = checked_source(extra, "managed-core/files/" + path)
                sha256 = copy(source, staging / "managed-core/files" / path, entry["mode"])
                if sha256 != entry["sha256"]:
                    raise ValueError("Extra managed asset SHA-256 mismatch: " + path)
                files[path] = dict(path=path, sha256=sha256, mode=entry["mode"])
        if not files:
            raise ValueError("Empty managed asset manifest")
        manifest = dict(schemaVersion=1, sourceSha=source_sha, files=[files[path] for path in sorted(files)])
        (staging / "managed-core/manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
        (staging / MARKER).write_text(MARKER_TEXT)
        if output.exists():
            # Only a previously generated tree can reach here. Retain it until publication succeeds.
            previous = Path(tempfile.mkdtemp(prefix=".managed-previous-", dir=output.parent))
            previous.rmdir()
            output.rename(previous)
            try:
                staging.rename(output)
            except OSError:
                previous.rename(output)
                raise
            shutil.rmtree(previous)
        else:
            staging.rename(output)
        print(f"Prepared {len(files)} managed files from {source_sha[:12]} in {output}")
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--extra-dir", type=Path)
    parser.add_argument("--source-sha", help="Explicit build provenance for source archives without .git")
    args = parser.parse_args()
    try:
        build(args.repo.resolve(), args.output_dir.absolute(), args.extra_dir.absolute() if args.extra_dir else None, args.source_sha)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f"Managed Android asset preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
