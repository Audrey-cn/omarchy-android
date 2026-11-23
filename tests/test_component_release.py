"""Validate release archives without installing their payload."""

import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(script):
    path = ROOT / "scripts" / (script + ".py")
    spec = importlib.util.spec_from_file_location(script, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


desktop = load("build-desktop-component")
verifier = load("verify-component-release")
VERSION = "1.0.0"
ARM64_ELF = b"\x7fELF\x02\x01\x01" + b"\x00" * 11 + b"\xb7\x00"


class ComponentRelease(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        desktop.build(VERSION, self.directory, "fixture release")
        self.source = json.loads((self.directory / "desktop-component.json").read_text())["source_commit"]
        self.build_runtime("browser")
        self.build_runtime("graphics")

    def build_runtime(self, kind, extra_payload=None, extra_control=None, dependencies="libc6"):
        package = f"omarchy-{kind}-runtime"
        basename = f"{package}_{VERSION}_arm64"
        docs = f"usr/share/doc/{package}"
        sources = [{"id": kind, "sha256": "f" * 64}]
        lock = json.dumps({"sources": sources}).encode()
        payload = {
            f"{docs}/sources.json": (lock, 0o644),
            f"{docs}/legacy.json.xz": (b"legacy receipt", 0o644),
            f"{docs}/copyright": (b"copyright", 0o644),
        }
        if kind == "graphics":
            payload.update({
                f"{verifier.GRAPHICS_PREFIX}/bin/{command}": (ARM64_ELF, 0o755)
                for command in ("sway", "swaymsg", "swaybar", "swaynag")
            })
            payload.update({
                f"{verifier.GRAPHICS_PREFIX}/lib/aarch64-linux-gnu/{name}.so.1": (ARM64_ELF, 0o644)
                for name in ("libwayland-client", "libwayland-server", "libinput", "libwlroots-0.18")
            })
        else:
            chromium, node = verifier.BROWSER_PREFIXES
            receipt = json.dumps({"sourceSha": self.source, "sources": sources,
                                  "sourceManifestSha256": hashlib.sha256(lock).hexdigest()}).encode()
            payload.update({
                f"{chromium}/source-manifest.json": (receipt, 0o644),
                f"{chromium}/browsers/chromium-1134/chrome-linux/chrome": (ARM64_ELF, 0o755),
                f"{node}/node-v22.0.0-linux-arm64/bin/node": (ARM64_ELF, 0o755),
            })
        payload.update(extra_payload or {})
        metadata = {
            "package": package, "version": VERSION, "architecture": "arm64",
            "sourceSha": self.source, "os": "ubuntu", "osVersion": "24.04",
            "runtimeApi": 1, "minAppVersionCode": 64, "depends": ["libc6"], "sources": sources,
        }
        manifest = dict(metadata, files={
            name: hashlib.sha256(data).hexdigest() for name, (data, _) in payload.items()
        })
        payload[f"{docs}/manifest.json"] = (json.dumps(manifest).encode(), 0o644)
        control = {
            "control": (
                f"Package: {package}\nVersion: {VERSION}\nArchitecture: arm64\n"
                f"Depends: {dependencies}\nDescription: fixture\n".encode(),
                0o644,
            )
        }
        control.update(extra_control or {})
        members = [("debian-binary", b"2.0\n"), ("control.tar.gz", desktop.tar(control)),
                   ("data.tar.gz", desktop.tar(payload))]
        archive = bytearray(b"!<arch>\n")
        for name, data in members:
            archive.extend(f"{name:<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode())
            archive.extend(data)
            if len(data) % 2:
                archive.extend(b"\n")
        deb = self.directory / (basename + ".deb")
        deb.write_bytes(archive)
        metadata.update(asset=deb.name, bytes=len(archive), sha256=hashlib.sha256(archive).hexdigest())
        (self.directory / (basename + ".json")).write_text(json.dumps(metadata))

    def verify(self):
        return verifier.verify_release(self.directory, VERSION, self.source)

    def test_release_cli_and_evidence(self):
        evidence = self.verify()
        self.assertEqual([entry["kind"] for entry in evidence["components"]],
                         ["desktop", "browser", "graphics"])
        self.assertFalse((self.directory / "SHA256SUMS").exists())
        subprocess.run(
            ["python3", str(ROOT / "scripts/verify-component-release.py"), str(self.directory),
             "--source-sha", self.source, "--version", VERSION, "--write-evidence"],
            check=True, capture_output=True,
        )
        actual = json.loads((self.directory / "release-verification.json").read_text())
        self.assertEqual(actual["sha256"], evidence["sha256"])
        checksums = (self.directory / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(checksums), 6)
        for line in checksums:
            checksum, name = line.split("  ", 1)
            self.assertEqual(checksum, hashlib.sha256((self.directory / name).read_bytes()).hexdigest())

    @unittest.skipUnless(shutil.which("dpkg-deb"), "requires Debian archive tools")
    def test_debian_zstd_archives(self):
        for kind in ("browser", "graphics"):
            basename = f"omarchy-{kind}-runtime_{VERSION}_arm64"
            deb = self.directory / (basename + ".deb")
            descriptor = self.directory / (basename + ".json")
            with tempfile.TemporaryDirectory() as unpacked:
                subprocess.run(["dpkg-deb", "--raw-extract", deb, unpacked], check=True)
                control = Path(unpacked) / "DEBIAN/control"
                control.write_text(control.read_text() + "Maintainer: Fixture <fixture@example.com>\n")
                subprocess.run(["dpkg-deb", "--root-owner-group", "-Zzstd", "--build", unpacked, deb],
                               check=True, capture_output=True)
            metadata = json.loads(descriptor.read_bytes())
            data = deb.read_bytes()
            metadata.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            descriptor.write_text(json.dumps(metadata))
        self.verify()

    def test_rejects_debian_payload_even_when_descriptor_and_manifest_match(self):
        for payload in ("root/.config/user-data", "usr/local/bin/sway"):
            with self.subTest(payload=payload):
                self.build_runtime("graphics", extra_payload={payload: (b"unexpected", 0o644)})
                with self.assertRaisesRegex(ValueError, "outside private prefixes"):
                    self.verify()
        self.build_runtime("graphics", extra_control={"postinst": (b"exit 1", 0o755)})
        with self.assertRaisesRegex(ValueError, "unexpected control archive entry"):
            self.verify()

    def test_rejects_release_identity_control_dependency_and_descriptor_mismatches(self):
        with self.assertRaisesRegex(ValueError, "descriptor identity"):
            verifier.verify_release(self.directory, VERSION, "0" * 40)
        self.build_runtime("graphics", dependencies="libc6, unexpected")
        with self.assertRaisesRegex(ValueError, "dependencies do not match"):
            self.verify()
        descriptor = self.directory / f"omarchy-graphics-runtime_{VERSION}_arm64.json"
        metadata = json.loads(descriptor.read_text())
        metadata["sha256"] = "0" * 64
        descriptor.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "descriptor SHA-256 mismatch"):
            self.verify()
