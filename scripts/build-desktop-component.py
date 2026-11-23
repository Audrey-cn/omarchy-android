#!/usr/bin/env python3
"""Build a reproducible Debian archive from the tracked desktop command inputs."""

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "omarchy-android-desktop"
PREFIX = "usr/lib/omarchy-android/desktop/"
VERSION = r"(?:0|[1-9][0-9]{0,8})(?:\.(?:0|[1-9][0-9]{0,8})){2}"


def encoded(obj):
    return (json.dumps(obj, sort_keys=True, indent=2) + "\n").encode()


def tar(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        directories = {
            str(parent)
            for name in entries
            for parent in Path(name).parents
            if str(parent) != "."
        }
        for directory in sorted(directories):
            item = tarfile.TarInfo("./" + directory)
            item.type = tarfile.DIRTYPE
            item.mode = 0o755
            archive.addfile(item)
        for name, (data, mode) in sorted(entries.items()):
            item = tarfile.TarInfo("./" + name)
            item.mode = mode
            item.size = len(data)
            archive.addfile(item, io.BytesIO(data))
    return gzip.compress(stream.getvalue(), mtime=0)


def build(version, output, changes, apk_min=64, runtime_min=1):
    if not re.fullmatch(VERSION, version) or min(apk_min, runtime_min) < 0:
        raise ValueError("invalid version or compatibility requirement")
    sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    files = {}
    ownership = (ROOT / "core/config/component-ownership.json").read_bytes()
    for relative in json.loads(ownership)["commands"]:
        source = ROOT / relative
        if (
            source.parent not in (ROOT / "core/bin", ROOT / "core/ai-suite")
            or source.is_symlink()
        ):
            raise ValueError("Invalid desktop command input: " + relative)
        data = source.read_bytes()
        if not data.startswith(b"#!") or "bin/" + source.name in files:
            raise ValueError("Invalid or duplicate command: " + relative)
        files["bin/" + source.name] = (data, 0o755)
    for name in ("sway/config", "fcitx5/profile"):
        files["defaults/" + name] = ((ROOT / "core/config" / name).read_bytes(), 0o644)
    for name in ("LICENSE", "LICENSES.md"):
        files[name] = ((ROOT / name).read_bytes(), 0o644)
    files["ownership.json"] = (ownership, 0o644)
    deps = "python3, python3-gi, gir1.2-glib-2.0, libgtk-4-1, fcitx5-frontend-gtk4, bash, dpkg, ca-certificates, pulseaudio, pulseaudio-utils"
    manifest = dict(
        package=PACKAGE,
        version=version,
        source_commit=sha,
        depends=deps,
        files={k: hashlib.sha256(v[0]).hexdigest() for k, v in files.items()},
    )
    files["manifest.json"] = (encoded(manifest), 0o644)
    control = f"Package: {PACKAGE}\nVersion: {version}\nArchitecture: all\nMaintainer: Omarchy Android <omarchy-android@users.noreply.github.com>\nDepends: {deps}\nDescription: Omarchy Android guest desktop commands and factory defaults\n"
    data = bytearray(b"!<arch>\n")
    for name, content in [
        ("debian-binary", b"2.0\n"),
        ("control.tar.gz", tar({"control": (control.encode(), 0o644)})),
        ("data.tar.gz", tar({PREFIX + k: v for k, v in files.items()})),
    ]:
        data.extend(
            f"{name:<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(content):<10}`\n".encode()
        )
        data.extend(content)
        if len(content) % 2:
            data.extend(b"\n")
    output.mkdir(parents=True, exist_ok=True)
    filename = f"{PACKAGE}_{version}_all.deb"
    (output / filename).write_bytes(data)
    catalog = dict(
        schema=1,
        package=PACKAGE,
        version=version,
        filename=filename,
        sha256=hashlib.sha256(data).hexdigest(),
        size=len(data),
        changes=changes,
        source_commit=sha,
        compatibility=dict(
            os="ubuntu",
            codename="noble",
            architecture="arm64",
            runtime_kinds=["proot", "chroot"],
            min_apk_version_code=apk_min,
            min_runtime_api=runtime_min,
        ),
    )
    (output / "desktop-component.json").write_bytes(encoded(catalog))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--changes", required=True)
    parser.add_argument("--min-apk-version-code", type=int, default=64)
    parser.add_argument("--min-runtime-api", type=int, default=1)
    args = parser.parse_args()
    build(
        args.version,
        args.output,
        args.changes,
        args.min_apk_version_code,
        args.min_runtime_api,
    )
