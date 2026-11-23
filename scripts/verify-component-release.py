#!/usr/bin/env python3
"""Verify guest component archives and their release descriptors."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
import tarfile
import tempfile

COMPONENTS = ("desktop", "browser", "graphics")
DESKTOP = "omarchy-android-desktop"
DESKTOP_PREFIX = "usr/lib/omarchy-android/desktop"
BROWSER_PREFIXES = ("opt/omarchy-chromium128", "opt/omarchy-node")
GRAPHICS_PREFIX = "opt/omarchy-graphics"
VERSION = r"(?:0|[1-9][0-9]{0,8})(?:\.(?:0|[1-9][0-9]{0,8})){2}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def read_json(data):
    value = json.loads(data, object_pairs_hook=unique_object)
    require(isinstance(value, dict), "expected a JSON object")
    return value


def digest(stream, initial=b""):
    checksum = hashlib.sha256(initial)
    for block in iter(lambda: stream.read(1 << 20), b""):
        checksum.update(block)
    return checksum.hexdigest()


def regular_file(path):
    require(path.is_file() and not path.is_symlink(), f"missing regular file {path.name}")
    return path


@contextmanager
def tar_members(deb, member):
    if member.endswith(".zst"):
        option = "--ctrl-tarfile" if member.startswith("control.") else "--fsys-tarfile"
        command = ["dpkg-deb", option, str(deb)]
    else:
        command = ["ar", "p", str(deb), member]
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        try:
            with tarfile.open(fileobj=process.stdout, mode="r|*") as archive:
                yield archive
            # tarfile can stop at the end marker before the subprocess closes.
            while process.stdout.read(1 << 20):
                pass
            require(process.wait() == 0, f"cannot read {member} from {deb.name}")
        finally:
            if process.poll() is None:
                process.kill()


def member_name(item):
    name = item.name
    if name == "." and item.isdir():
        return ""
    name = name.removeprefix("./")
    require(
        all(part not in ("", ".", "..") for part in name.split("/"))
        and not any(ord(char) < 32 or ord(char) == 127 for char in name),
        f"unsafe archive path {item.name!r}",
    )
    return name


def small_file(archive, item):
    require(item.size <= 16 * (1 << 20), f"metadata too large: {item.name}")
    return archive.extractfile(item).read()


def read_control(archive):
    control = None
    for item in archive:
        name = member_name(item)
        if not name and item.isdir():
            continue
        require(name == "control" and item.isfile() and control is None,
                f"unexpected control archive entry {item.name!r}")
        control = small_file(archive, item).decode("utf-8")
    require(control is not None, "missing Debian control record")
    fields = {}
    for line in control.strip().splitlines():
        field, sep, value = line.partition(":")
        key = field.lower()
        require(sep and re.fullmatch(r"[a-z][a-z0-9-]*", key) and key not in fields,
                "malformed or duplicate Debian control field")
        fields[key] = value.strip()
    require(not fields.keys() & {"pre-depends", "breaks", "replaces", "conflicts", "provides"},
            "unexpected Debian package relationship")
    return fields


def inside(name, prefix):
    return name == prefix or name.startswith(prefix + "/")


def read_payload(archive, prefixes, manifest_name, metadata_names):
    entries, directories, tracked = {}, set(), {"executables": set(), "arm64": set()}
    for item in archive:
        name = member_name(item)
        require(name not in entries and name not in directories, f"duplicate payload path {name!r}")
        require(item.uid == item.gid == 0 and not item.mode & 0o7000,
                f"unexpected ownership or privilege bits: {name}")
        prefix = next((p for p in prefixes if inside(name, p)), None)
        if item.isdir():
            require(not name or prefix or any(inside(p, name) for p in prefixes),
                    f"payload directory outside private prefixes: {name}")
            directories.add(name)
        elif item.isfile():
            require(prefix and name != prefix, f"payload outside private prefixes: {name}")
            if name == manifest_name:
                tracked["manifest"] = read_json(small_file(archive, item))
            elif name in metadata_names:
                data = small_file(archive, item)
                tracked[name] = read_json(data)
                entries[name] = hashlib.sha256(data).hexdigest()
            else:
                stream = archive.extractfile(item)
                if item.mode & 0o111:
                    tracked["executables"].add(name)
                    header = stream.read(20)
                    if header[:7] == b"\x7fELF\x02\x01\x01" and header[18:20] == b"\xb7\x00":
                        tracked["arm64"].add(name)
                    entries[name] = digest(stream, header)
                else:
                    entries[name] = digest(stream)
            # Record the manifest too, so repeated paths and child paths are rejected.
            entries.setdefault(name, None)
        elif item.issym():
            require(prefix and prefix != DESKTOP_PREFIX and name != prefix,
                    f"unexpected symbolic link: {name}")
            target = item.linkname
            normalized = posixpath.normpath(
                target.lstrip("/") if target.startswith("/") else
                posixpath.join(posixpath.dirname(name), target)
            )
            target_segments = target.lstrip("/").split("/")
            while target_segments and target_segments[0] == ".." and not target.startswith("/"):
                target_segments.pop(0)
            require(target and inside(normalized, prefix)
                    and all(part not in ("", ".", "..") for part in target_segments)
                    and not any(ord(c) < 32 or ord(c) == 127 for c in target),
                    f"symbolic link leaves its private prefix: {name}")
            entries[name] = "link:" + target
        else:
            raise ValueError(f"unsupported payload entry {item.name!r}")
    for name in entries.keys() | directories:
        require(not any(parent.as_posix() in entries for parent in Path(name).parents),
                f"payload path passes through a non-directory: {name}")
    require("manifest" in tracked, "missing internal manifest")
    del entries[manifest_name]
    return entries, tracked


def archive_manifests(deb, prefixes, manifest_name, metadata_names):
    # Inspect the ar envelope as well as each tar. No archive is extracted to disk.
    members = subprocess.check_output(["ar", "t", str(deb)], text=True).splitlines()
    compression = r"(?:\.(?:gz|xz|bz2|lzma|zst))?"
    require(
        len(members) == 3 and members[0] == "debian-binary"
        and re.fullmatch(r"control\.tar" + compression, members[1])
        and re.fullmatch(r"data\.tar" + compression, members[2]),
        "unexpected Debian archive members",
    )
    require(subprocess.check_output(["ar", "p", str(deb), "debian-binary"]) == b"2.0\n",
            "unexpected Debian archive format")
    with tar_members(deb, members[1]) as archive:
        control = read_control(archive)
    with tar_members(deb, members[2]) as archive:
        entries, tracked = read_payload(archive, prefixes, manifest_name, metadata_names)
    return control, entries, tracked


def verify_component(directory, kind, version, source_sha):
    desktop = kind == "desktop"
    package = DESKTOP if desktop else f"omarchy-{kind}-runtime"
    arch = "all" if desktop else "arm64"
    basename = f"{package}_{version}_{arch}"
    doc_prefix = DESKTOP_PREFIX if desktop else f"usr/share/doc/{package}"
    runtime_prefixes = BROWSER_PREFIXES if kind == "browser" else (GRAPHICS_PREFIX,)
    prefixes = (doc_prefix,) if desktop else (doc_prefix, *runtime_prefixes)
    manifest_name = doc_prefix + "/manifest.json"
    descriptor = regular_file(directory / ("desktop-component.json" if desktop else basename + ".json"))
    descriptor_bytes = descriptor.read_bytes()
    metadata = read_json(descriptor_bytes)
    deb = regular_file(directory / (basename + ".deb"))
    name_field, size_field, source_field = (
        ("filename", "size", "source_commit") if desktop else ("asset", "bytes", "sourceSha")
    )
    identity = {"package": package, "version": version, source_field: source_sha}
    require(all(metadata.get(key) == value for key, value in identity.items()),
            "descriptor identity does not match release")
    require(metadata.get(name_field) == deb.name, "descriptor filename mismatch")
    require(type(metadata.get(size_field)) is int and metadata[size_field] == deb.stat().st_size,
            "descriptor size mismatch")
    with deb.open("rb") as stream:
        deb_sha = digest(stream)
    require(metadata.get("sha256") == deb_sha, "descriptor SHA-256 mismatch")
    if desktop:
        compatibility = metadata.get("compatibility")
        require(type(metadata.get("schema")) is int and metadata["schema"] == 1
                and isinstance(compatibility, dict)
                and compatibility.get("architecture") == "arm64"
                and compatibility.get("os") == "ubuntu"
                and compatibility.get("codename") == "noble"
                and compatibility.get("runtime_kinds") == ["proot", "chroot"]
                and all(type(compatibility.get(key)) is int and compatibility[key] >= 0
                        for key in ("min_apk_version_code", "min_runtime_api")),
                "unexpected desktop compatibility descriptor")
    else:
        require(metadata.get("architecture") == arch and metadata.get("os") == "ubuntu"
                and metadata.get("osVersion") == "24.04"
                and all(type(metadata.get(key)) is int and metadata[key] >= 0
                        for key in ("runtimeApi", "minAppVersionCode")),
                "unexpected runtime compatibility descriptor")
    source_lock = f"{doc_prefix}/sources.json"
    browser_receipt = f"{BROWSER_PREFIXES[0]}/source-manifest.json"
    metadata_names = () if desktop else (source_lock, browser_receipt)
    control, entries, tracked = archive_manifests(deb, prefixes, manifest_name, metadata_names)
    require(all(control.get(key) == value for key, value in
                {"package": package, "version": version, "architecture": arch}.items()),
            "Debian control identity does not match release")
    manifest = tracked["manifest"]
    require(all(manifest.get(key) == value for key, value in identity.items()),
            "internal manifest identity does not match release")
    if desktop:
        expected_entries = {name[len(doc_prefix) + 1:]: value for name, value in entries.items()}
        dependency_text = manifest.get("depends")
        depends = dependency_text.split(",") if isinstance(dependency_text, str) else None
        require({"LICENSE", "LICENSES.md", "ownership.json", "defaults/sway/config",
                 "defaults/fcitx5/profile"} <= expected_entries.keys()
                and any(name.startswith(doc_prefix + "/bin/") for name in tracked["executables"]),
                "missing desktop commands, defaults or ownership metadata")
    else:
        expected_entries = entries
        depends = metadata.get("depends")
        require(all(manifest.get(key) == value for key, value in metadata.items()
                    if key not in (name_field, size_field, "sha256")),
                "internal runtime metadata does not match descriptor")
        required_docs = {f"{doc_prefix}/{name}" for name in ("sources.json", "legacy.json.xz", "copyright")}
        require(required_docs <= entries.keys(), "missing runtime provenance files")
        require(isinstance(metadata.get("sources"), list) and metadata["sources"]
                and tracked.get(source_lock, {}).get("sources") == metadata["sources"],
                "runtime source lock does not match descriptor")
        executables = tracked["arm64"]
        if kind == "graphics":
            require({f"{GRAPHICS_PREFIX}/bin/{command}"
                     for command in ("sway", "swaymsg", "swaybar", "swaynag")} <= executables,
                    "missing arm64 graphics executables")
            lib_directory = re.escape(GRAPHICS_PREFIX) + r"/lib/(?:aarch64-linux-gnu/)?"
            require(all(any(re.fullmatch(lib_directory + library + r"\.so(?:\.[0-9]+)*", path)
                            for path in entries)
                        for library in ("libwayland-client", "libwayland-server", "libinput", r"libwlroots(?:-[0-9.]+)?")),
                    "missing private graphics shared libraries")
        else:
            receipt = tracked.get(browser_receipt, {})
            require(receipt.get("sourceSha") == source_sha
                    and receipt.get("sourceManifestSha256") == entries[source_lock]
                    and receipt.get("sources") == metadata["sources"],
                    "browser build receipt does not match source lock or commit")
            require(any(re.fullmatch(re.escape(BROWSER_PREFIXES[0])
                                     + r"/browsers/chromium-[0-9]+/chrome-linux/chrome", name)
                        for name in executables)
                    and any(re.fullmatch(re.escape(BROWSER_PREFIXES[1])
                                         + r"/node-v[0-9.]+-linux-arm64/bin/node", name)
                            for name in executables),
                    "missing arm64 browser or Node executable")
    require(manifest.get("files") == expected_entries, "internal manifest payload does not match archive")
    require(
        isinstance(depends, list) and bool(depends)
        and all(isinstance(dep, str) and re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", dep.strip()) for dep in depends)
        and [dep.strip() for dep in depends] == [dep.strip() for dep in control.get("depends", "").split(",")],
        "Debian control dependencies do not match metadata",
    )
    return {
        "kind": kind,
        "package": package,
        "architecture": arch,
        "privatePrefixes": prefixes,
        "payloadEntries": len(entries),
        "depends": [dep.strip() for dep in depends],
        "sha256": {deb.name: deb_sha, descriptor.name: hashlib.sha256(descriptor_bytes).hexdigest()},
    }


def verify_release(directory, version, source_sha, kinds=COMPONENTS):
    require(re.fullmatch(VERSION, version), "expected a release version like 1.0.0")
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha), "expected the full lowercase source commit SHA")
    directory = directory.resolve()
    selected = set(kinds)
    require(selected and selected <= set(COMPONENTS), "select at least one known component")
    components = []
    for kind in (kind for kind in COMPONENTS if kind in selected):
        try:
            components.append(verify_component(directory, kind, version, source_sha))
        except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError) as exc:
            raise ValueError(f"{kind}: {exc}") from exc
    files = {name: value for component in components for name, value in component["sha256"].items()}
    expected_debs = {name for name in files if name.endswith(".deb")}
    require({path.name for path in directory.glob("*.deb")} == expected_debs,
            "release directory contains unexpected Debian packages")
    return {"schema": 1, "sourceSha": source_sha, "version": version, "components": components,
            "sha256": files}


def write_evidence(directory, evidence):
    outputs = {
        "SHA256SUMS": "".join(f"{checksum}  {name}\n" for name, checksum in sorted(evidence["sha256"].items())),
        "release-verification.json": json.dumps(evidence, indent=2, sort_keys=True) + "\n",
    }
    for name, content in outputs.items():
        with tempfile.NamedTemporaryFile(dir=directory, mode="w", encoding="utf-8", delete=False) as stream:
            temporary = Path(stream.name)
            try:
                stream.write(content)
                stream.close()
                os.replace(temporary, directory / name)
            finally:
                temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--component", action="append", choices=COMPONENTS,
                        help="Verify a selected component (repeatable, defaults to all components)")
    parser.add_argument("--write-evidence", action="store_true")
    args = parser.parse_args()
    try:
        evidence = verify_release(args.directory, args.version, args.source_sha, args.component or COMPONENTS)
        if args.write_evidence:
            write_evidence(args.directory, evidence)
    except (ValueError, OSError) as exc:
        parser.exit(1, f"{exc}\n")
    names = ", ".join(component["kind"] for component in evidence["components"])
    print(f"Verified {names} {args.version} from {args.source_sha}")


if __name__ == "__main__":
    main()
