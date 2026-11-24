#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Shared Android test-version and signing policy. GPL-3.0-or-later."""

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile


ALIAS = "omarchy-android-test"
PASSWORD_ENV = "OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD"
CANONICAL_REPO = "Audrey-cn/omarchy-android"
NATIVE_NAMES = {"libproot-omarchy-readonly.so", "libproot-omarchy-loader-aarch64.so", "libproot-omarchy-loader-arm.so"}


def command(args, *, cwd=None, safe_error=None):
    result = subprocess.run(args, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True)
    if result.returncode:
        # Tool diagnostics may contain private material; only emit caller-owned errors.
        raise ValueError(safe_error or "Build metadata command failed")
    return result.stdout


def version(repo):
    def git(*args):
        return command(["git", *args], cwd=repo).decode().strip()

    if git("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Versioning requires full Git history; use checkout fetch-depth: 0")
    properties = {}
    for line in (repo / "apps/android/version.properties").read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    release = "releaseVersionCode" in properties or "releaseVersionName" in properties
    if release and (not re.fullmatch(r"[1-9][0-9]*", properties.get("releaseVersionCode", ""))
                    or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", properties.get("releaseVersionName", ""))):
        raise ValueError("Explicit Android release version requires a valid code and name")
    base = int(properties["versionCodeBase"])
    major, minor = int(properties["versionMajor"]), int(properties["versionMinor"])
    code = int(properties["releaseVersionCode"]) if release else base + int(git("rev-list", "--count", "HEAD"))
    if not 1 < code <= 2_100_000_000 or min(base, major, minor) < 0:
        raise ValueError("Android version properties are outside supported bounds")
    sha = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--untracked-files=normal"))
    name = properties["releaseVersionName"] if release else f'{major}.{minor}.{code}-test.g{sha[:12]}'
    return dict(versionCode=code, versionName=name + (".dirty" if dirty and not release else ""),
                sourceSha=sha, sourceDirty=dirty, releaseVersion=release)


def write_private(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
        stream.write(data)


def write_metadata(path, metadata):
    path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")


def github_lines(variable, values):
    destination = os.environ.get(variable)
    if destination:
        with open(destination, "a") as stream:
            for key, value in values.items():
                if "\n" in str(value) or "\r" in str(value):
                    raise ValueError("Invalid multiline build metadata")
                stream.write(f"{key}={value}\n")


def prepare(repo, output):
    metadata = version(repo)
    native = native_runtime(repo)
    if native is not None:
        metadata["expectedNativeRuntime"] = native
    trusted = (os.environ.get("GITHUB_ACTIONS") == "true"
               and os.environ.get("GITHUB_REPOSITORY") == CANONICAL_REPO
               and os.environ.get("GITHUB_REF") == "refs/heads/main"
               and os.environ.get("GITHUB_EVENT_NAME") in {"push", "workflow_dispatch"})
    mode = "stable-test" if trusted else (
        "temporary-pr" if os.environ.get("GITHUB_EVENT_NAME") == "pull_request" else "temporary-local")
    metadata.update(signingMode=mode, upgradeable=False, packageName="com.omarchy.android")
    if not trusted and not metadata["releaseVersion"]:
        metadata["versionName"] += ".temporary"
    if output.is_symlink():
        raise ValueError("Signing output must not be a symlink")
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    output.chmod(0o700)
    key = output / "test-signing.p12"
    if trusted:
        encoded = os.environ.get("OMARCHY_ANDROID_TEST_KEYSTORE_B64", "")
        if not encoded or not os.environ.get(PASSWORD_ENV):
            raise ValueError("Trusted main build requires both Android test-signing secrets")
        if metadata["sourceDirty"]:
            raise ValueError("Stable signing requires an unchanged checkout")
        try:
            data = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError):
            raise ValueError("Invalid Android test keystore encoding") from None
        if not data:
            raise ValueError("Empty Android test keystore")
        write_private(key, data)
        try:
            args = ["keytool", "-J-Duser.language=en", "-J-Duser.country=US", "-storetype", "PKCS12",
                    "-keystore", str(key), "-alias", ALIAS, "-storepass:env", PASSWORD_ENV]
            listing = command(args + ["-list", "-v"], safe_error="Cannot read Android test signing key")
            if b"PrivateKeyEntry" not in listing:
                raise ValueError("Android test alias does not contain a private key")
            certificate = command(args + ["-exportcert"], safe_error="Cannot export test signing certificate")
            metadata["expectedCertificateSha256"] = hashlib.sha256(certificate).hexdigest()
        except (ValueError, OSError):
            key.unlink(missing_ok=True)
            raise
    metadata_path = output / "build-metadata.json"
    write_metadata(metadata_path, metadata)
    values = {"OMARCHY_ANDROID_VERSION_CODE": metadata["versionCode"],
              "OMARCHY_ANDROID_VERSION_NAME": metadata["versionName"],
              "OMARCHY_ANDROID_SOURCE_SHA": metadata["sourceSha"],
              "OMARCHY_ANDROID_SIGNING_MODE": mode,
              "OMARCHY_ANDROID_BUILD_METADATA": metadata_path,
              "OMARCHY_ANDROID_KEYSTORE_PATH": key if trusted else ""}
    github_lines("GITHUB_ENV", values)
    github_lines("GITHUB_OUTPUT", {"artifact_suffix": "" if trusted else "-" + mode})
    print(f"Android {metadata['versionName']}: {mode}; APK verification still required")


def generate_keystore(output, openssl):
    # Refuse replacement: regenerating this key would break installed-app updates.
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    output.chmod(0o700)
    try:
        password = output / "password.txt"
        write_private(password, secrets.token_urlsafe(48).encode())
        with tempfile.TemporaryDirectory(prefix=".generate-", dir=output) as temp:
            private = Path(temp) / "private.pem"
            certificate = Path(temp) / "certificate.pem"
            command([openssl, "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
                     "-out", str(private)], safe_error="OpenSSL RSA key generation failed")
            private.chmod(0o600)
            command([openssl, "req", "-new", "-x509", "-sha256", "-days", "10000", "-key", str(private),
                     "-subj", "/CN=Omarchy Android Test/", "-out", str(certificate)],
                    safe_error="OpenSSL test certificate generation failed")
            key = output / "test-signing.p12"
            command([openssl, "pkcs12", "-export", "-inkey", str(private), "-in", str(certificate),
                     "-name", ALIAS, "-keypbe", "AES-256-CBC", "-certpbe", "AES-256-CBC",
                     "-macalg", "SHA256", "-out", str(key), "-passout", "file:" + str(password)],
                    safe_error="OpenSSL PKCS12 export failed")
            key.chmod(0o600)
        write_private(output / "test-signing.keystore.b64", base64.b64encode(key.read_bytes()))
    except (OSError, ValueError):
        shutil.rmtree(output)
        raise
    print(f"Created private signing files in {output}; keep this directory outside the repository")


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key in managed manifest")
        result[key] = value
    return result


def checked_repo_file(repo, relative):
    path = repo
    for part in relative.split("/"):
        path = path / part
        if path.is_symlink():
            raise ValueError("Native runtime source must not contain symlinks")
    if not path.is_file():
        raise ValueError("Missing native runtime source file: " + relative)
    return path


def sha256_stream(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def native_runtime(repo):
    relative = "runtime/proot-omarchy/manifest.json"
    manifest_path = repo / relative
    source_prefix = "apps/android/app/src/main/jniLibs/arm64-v8a/"
    if not manifest_path.exists() and not manifest_path.is_symlink():
        if any((repo / source_prefix / name).exists() or (repo / source_prefix / name).is_symlink() for name in NATIVE_NAMES):
            raise ValueError("Native runtime binaries require their source manifest")
        return None  # Older/source-only fixtures without the independent runtime.
    path = checked_repo_file(repo, relative)
    manifest = json.loads(path.read_text(), object_pairs_hook=unique_json_object)
    if not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported native runtime source manifest")
    runtime_id = manifest.get("runtimeId")
    if not isinstance(runtime_id, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", runtime_id):
        raise ValueError("Invalid native runtime identity")
    files = manifest.get("files")
    if not isinstance(files, list) or len(files) != 3:
        raise ValueError("Native runtime manifest must contain all three files")
    expected_paths = {"lib/arm64-v8a/" + name for name in NATIVE_NAMES}
    normalized = []
    roles, paths = set(), set()
    for entry in files:
        if not isinstance(entry, dict):
            raise ValueError("Invalid native runtime manifest entry")
        role, apk_path = entry["role"], entry["apkPath"]
        checksum, size = entry["sha256"], entry["size"]
        if not isinstance(role, str) or not role or role in roles or apk_path not in expected_paths or apk_path in paths:
            raise ValueError("Duplicate or unapproved native runtime member")
        if not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum) or type(size) is not int or size <= 0:
            raise ValueError("Invalid native runtime SHA-256/size")
        source_path = source_prefix + apk_path.rsplit("/", 1)[1]
        if entry["sourcePath"] != source_path:
            raise ValueError("Unapproved native runtime source path")
        source = checked_repo_file(repo, source_path)
        with source.open("rb") as stream:
            checksum_actual = sha256_stream(stream)
        if source.stat().st_size != size or checksum_actual != checksum:
            raise ValueError("Native runtime source SHA-256/size mismatch: " + source_path)
        roles.add(role)
        paths.add(apk_path)
        normalized.append(dict(role=role, apkPath=apk_path, sha256=checksum, size=size))
    return dict(runtimeId=runtime_id, files=sorted(normalized, key=lambda file: file["apkPath"]))


def verify_native_runtime(apk, repo, metadata):
    native = native_runtime(repo)
    if native != metadata.get("expectedNativeRuntime"):
        raise ValueError("Native runtime source manifest changed after build preparation")
    if native is None:
        return {}
    with zipfile.ZipFile(apk) as archive:
        for entry in native["files"]:
            try:
                member = archive.getinfo(entry["apkPath"])
            except KeyError:
                raise ValueError("APK is missing native runtime member: " + entry["apkPath"]) from None
            if member.file_size != entry["size"]:
                raise ValueError("APK native runtime size differs from source: " + entry["apkPath"])
            with archive.open(member) as stream:
                checksum = sha256_stream(stream)
            if checksum != entry["sha256"]:
                raise ValueError("APK native runtime SHA-256 differs from source: " + entry["apkPath"])
    return dict(nativeRuntimeId=native["runtimeId"], nativeLibraryCount=3, nativeLibraries=native["files"])


def checked_zip_members(archive):
    members = archive.infolist()
    names = [member.filename for member in members]
    if len(names) != len(set(names)):
        raise ValueError("APK has duplicate ZIP member names")
    for member in members:
        parts = member.filename.rstrip("/").split("/")
        if any(part in {"", ".", ".."} for part in parts) or any(
                ord(c) < 32 or ord(c) == 127 or c == "\\" for c in member.filename):
            raise ValueError("APK has an ambiguous ZIP member path")
        if not member.is_dir() and stat.S_IFMT(member.external_attr >> 16) not in {0, stat.S_IFREG}:
            raise ValueError("APK has a non-regular ZIP member")
    return members


def check_guest_path(name):
    parts = name.rstrip("/").split("/")
    reserved = {"managed-core", "omarchy-node", "omarchy-chromium128", "rootfs.tar.zst"}
    if reserved.intersection(parts) or "/usr/local/bin/" in "/" + "/".join(parts) + "/":
        raise ValueError("Online installer contains a guest path: " + name)


def check_installer_inputs(repo):
    # Ignore rules affect Git only. Gradle merges these source directories even
    # when an old AllInOne artifact there is untracked.
    sources = repo / "apps/android/app/src"
    for source in sources.glob("*/assets"):
        for path in source.rglob("*"):
            check_guest_path(path.relative_to(sources).as_posix())


def check_tar_member(entry, asset):
    path = "/" + entry.name.strip("/") + "/"
    check_guest_path(entry.name)
    if any(path.endswith(marker) for marker in ("/.tiny.yaml/", "/etc/os-release/", "/etc/passwd/",
                                                "/usr/lib/os-release/", "/usr/bin/env/", "/bin/sh/")):
        raise ValueError("Online installer contains a guest filesystem: " + asset + "/" + entry.name)


def check_tar_contents(stream, asset):
    try:
        contents = tarfile.open(fileobj=stream, mode="r|*")
    except tarfile.ReadError:
        return  # Compressed application resources do not have to contain tar.
    with contents:
        for entry in contents:
            check_tar_member(entry, asset)
            if entry.isfile() and entry.size >= 4 and contents.extractfile(entry).read(4) == b"\x7fELF":
                raise ValueError("Online installer tar contains an ELF executable: " + asset + "/" + entry.name)


def check_guest_contents(archive, member):
    if member.is_dir():
        return
    name = member.filename
    with archive.open(member) as stream:
        header = stream.read(512)
        if header.startswith(b"\x7fELF"):
            if not re.fullmatch(r"lib/arm64-v8a/lib[^/]+\.so", name):
                raise ValueError("ELF executable outside the APK native library directory: " + name)
            return
        is_zstd = header.startswith(b"\x28\xb5\x2f\xfd")
        if not is_zstd and not header.startswith((b"\x1f\x8b", b"BZh", b"\xfd7zXZ\x00")):
            try:
                tarfile.TarInfo.frombuf(header, "utf-8", "surrogateescape")
            except tarfile.HeaderError:
                return
        stream.seek(0)
        if not is_zstd:
            check_tar_contents(stream, name)
            return
        # Python <3.14 tarfile supports gzip, bzip2 and xz natively. For a
        # zstd member give the decoder a real input fd and inspect its stream.
        with tempfile.TemporaryFile() as compressed:
            shutil.copyfileobj(stream, compressed)
            compressed.seek(0)
            with subprocess.Popen(["zstd", "-qdc", "-M64MB"], stdin=compressed,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
                try:
                    check_tar_contents(process.stdout, name)
                    # The decoder must also have succeeded for generic zstd resources.
                    while process.stdout.read(1024 * 1024):
                        pass
                    if process.wait() != 0:
                        raise ValueError("Cannot inspect zstd asset: " + name)
                finally:
                    process.stdout.close()


def verify_online_installer(apk, repo):
    catalog_name = "assets/initial-images.json"
    catalog = checked_repo_file(repo, "apps/android/app/src/main/" + catalog_name)
    with zipfile.ZipFile(apk) as archive:
        members = checked_zip_members(archive)
        for member in members:
            check_guest_path(member.filename)
            check_guest_contents(archive, member)
        packaged = archive.getinfo(catalog_name)
        if packaged.file_size != catalog.stat().st_size or packaged.file_size > 256 * 1024:
            raise ValueError("APK bundled image catalog has an unexpected size")
        data = archive.read(packaged)
        if data != catalog.read_bytes():
            raise ValueError("APK bundled image catalog differs from the checkout")
        parsed = json.loads(data, object_pairs_hook=unique_json_object)
        if (not isinstance(parsed, dict) or type(parsed.get("schemaVersion")) is not int
                or parsed["schemaVersion"] != 1 or not isinstance(parsed.get("images"), list)):
            raise ValueError("Invalid APK bundled image catalog")
    return dict(catalogSha256=hashlib.sha256(data).hexdigest(), managedFileCount=0, managedPayloadBytes=0)


def verify_managed_payload(apk, source_sha):
    prefix = "assets/managed-core/"
    payload_prefix = prefix + "files/"
    with zipfile.ZipFile(apk) as archive:
        members = checked_zip_members(archive)
        manifest = json.loads(archive.read(prefix + "manifest.json"), object_pairs_hook=unique_json_object)
        if not isinstance(manifest, dict):
            raise ValueError("APK managed manifest must be an object")
        if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
            raise ValueError("Unsupported APK managed manifest schema")
        if manifest.get("sourceSha") != source_sha:
            raise ValueError("APK managed manifest source differs from prepared metadata")
        entries = manifest.get("files")
        if not isinstance(entries, list) or not entries:
            raise ValueError("APK managed manifest must contain files")
        expected = {}
        for entry in entries:
            if not isinstance(entry, dict):
                raise ValueError("Invalid APK managed manifest entry")
            path, checksum, mode = entry["path"], entry["sha256"], entry["mode"]
            if not isinstance(path, str) or not isinstance(checksum, str):
                raise ValueError("Invalid APK managed file path/hash type")
            parts = path.split("/")
            if any(part in {"", ".", ".."} for part in parts) or any(
                    ord(c) < 32 or ord(c) == 127 or c == "\\" for c in path):
                raise ValueError("Invalid APK managed file path")
            if not ((len(parts) == 4 and parts[:3] == ["usr", "local", "bin"]) or
                    (len(parts) >= 3 and parts[0] == "opt" and parts[1] in {"omarchy-chromium128", "omarchy-node"})):
                raise ValueError("Unapproved APK managed file path")
            if not re.fullmatch(r"[0-9a-f]{64}", checksum) or type(mode) is not int or mode not in {420, 493}:
                raise ValueError("Invalid APK managed file hash/mode")
            name = payload_prefix + path
            if name in expected:
                raise ValueError("Duplicate APK managed manifest path")
            expected[name] = checksum
        actual = {member.filename for member in members
                  if member.filename.startswith(payload_prefix) and not member.is_dir()}
        if actual != set(expected):
            raise ValueError("APK managed payload inventory differs from its manifest (missing or unlisted files)")
        unexpected = [member.filename for member in members if not member.is_dir()
                      and member.filename.startswith(prefix) and member.filename != prefix + "manifest.json"
                      and member.filename not in actual]
        if unexpected:
            raise ValueError("APK contains unlisted managed metadata")
        payload_bytes = 0
        for name, checksum in expected.items():
            digest = hashlib.sha256()
            with archive.open(name) as stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                    payload_bytes += len(chunk)
            if digest.hexdigest() != checksum:
                raise ValueError("APK managed payload SHA-256 mismatch: " + name)
    return dict(managedSourceSha=source_sha, managedFileCount=len(expected), managedPayloadBytes=payload_bytes)


def verify(apk, metadata_path, build_tools, repo, delivery, require_non_debuggable=False):
    metadata = json.loads(metadata_path.read_text())
    # Clear any old success receipt before a retry, including a failed retry.
    metadata["upgradeable"] = False
    for key in ("delivery", "debuggable", "catalogSha256", "certificateSha256", "managedSourceSha", "managedFileCount", "managedPayloadBytes",
                "nativeRuntimeId", "nativeLibraryCount", "nativeLibraries"):
        metadata.pop(key, None)
    write_metadata(metadata_path, metadata)
    signer = command([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)],
                     safe_error="APK signature verification failed").decode()
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signer, re.M)
    if len(fingerprints) != 1:
        raise ValueError("APK must have exactly one signing certificate")
    fingerprint = fingerprints[0].lower()
    if metadata["signingMode"] == "stable-test" and fingerprint != metadata.get("expectedCertificateSha256"):
        raise ValueError("APK signing certificate differs from the persistent test key")
    badging = command([str(build_tools / "aapt"), "dump", "badging", str(apk)],
                      safe_error="Cannot inspect APK versions").decode()
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != (metadata["packageName"], str(metadata["versionCode"]), metadata["versionName"]):
        raise ValueError("APK package/version does not match prepared source metadata")
    debuggable = bool(re.search(r"^application-debuggable(?:[:\s]|$)", badging, re.M))
    if require_non_debuggable and debuggable:
        raise ValueError("Release APK must not be debuggable")
    if delivery is None:
        catalog = repo / "apps/android/app/src/main/assets/initial-images.json"
        delivery = "online-installer" if catalog.exists() else "managed"
    if delivery == "online-installer":
        if not metadata.get("expectedNativeRuntime"):
            raise ValueError("Online installer requires the native PRoot triplet")
        metadata.update(verify_online_installer(apk, repo))
    else:
        metadata.update(verify_managed_payload(apk, metadata["sourceSha"]))
    metadata.update(verify_native_runtime(apk, repo, metadata))
    metadata.update(delivery=delivery, debuggable=debuggable, certificateSha256=fingerprint,
                    upgradeable=metadata["signingMode"] == "stable-test")
    write_metadata(metadata_path, metadata)
    print(f"Verified APK {metadata['versionName']} ({metadata['signingMode']})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("version")
    commands.add_parser("check-installer-inputs")
    prep = commands.add_parser("prepare")
    prep.add_argument("--output-dir", type=Path, required=True)
    generate = commands.add_parser("generate-keystore")
    generate.add_argument("--output-dir", type=Path, required=True)
    generate.add_argument("--openssl", default="openssl")
    check = commands.add_parser("verify")
    check.add_argument("--delivery", choices=("managed", "online-installer"),
                       help="Defaults to online-installer in checkouts with an image catalog")
    check.add_argument("--require-non-debuggable", action="store_true",
                       help="Reject APKs with android:debuggable enabled (release delivery)")
    check.add_argument("--apk", type=Path, required=True)
    check.add_argument("--metadata", type=Path, required=True)
    check.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "version":
            print(json.dumps(version(args.repo.resolve()), sort_keys=True))
        elif args.action == "check-installer-inputs":
            check_installer_inputs(args.repo.resolve())
        elif args.action == "prepare":
            prepare(args.repo.resolve(), args.output_dir.absolute())
        elif args.action == "generate-keystore":
            generate_keystore(args.output_dir.absolute(), args.openssl)
        else:
            verify(args.apk, args.metadata, args.build_tools, args.repo.resolve(), args.delivery,
                   args.require_non_debuggable)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, tarfile.TarError) as error:
        # Do not print arbitrary tool output, key bytes, passwords or encoded secrets.
        print(f"Android build preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
