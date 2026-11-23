"""Check the release metadata against archival bytes, including relocated links."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts/prepare-initial-image.py"
SHA = "0123456789abcdef" * 2 + "01234567"
COMPONENT_SHA = "f" * 40


class InitialImageTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("prepare_initial_image", SCRIPT)
        self.image = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.image)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "release"
        self.output.mkdir()

    def tar(self, link_type=tarfile.SYMTYPE):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            for name, data in [(".tiny.yaml", b"config"), ("usr/bin/bash", b"binary"),
                               ("usr/bin/bash-copy", b"binary")]:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            member = tarfile.TarInfo("bin")
            member.type = link_type
            member.linkname = "usr/bin"
            archive.addfile(member)
        stream.seek(0)
        return stream

    def test_real_archive_size_hash_checkout_and_catalog(self):
        unpacked = self.image.unpacked_bytes(self.tar())
        self.assertEqual(unpacked, 18)
        compressed = self.output / "rootfs.tar.zst"
        compressed.write_bytes(b"compressed archive fixture")
        (self.output / "source.json").write_text("{}")
        self.image.write_metadata(self.output, SHA, unpacked)
        image = json.loads((self.output / "image.json").read_text())
        self.assertEqual(set(image), {"id", "name", "abi", "minAppVersionCode", "bytes",
                                     "unpackedBytes", "sha256", "sourceSha", "componentSourceSha", "urls"})
        self.assertEqual(image["id"], "desktop-ubuntu24-" + SHA[:12])
        self.assertEqual(image["abi"], "arm64-v8a")
        self.assertEqual(image["minAppVersionCode"], 64)
        self.assertEqual(image["bytes"], compressed.stat().st_size)
        self.assertEqual(image["unpackedBytes"], unpacked)
        self.assertEqual(image["sha256"], hashlib.sha256(compressed.read_bytes()).hexdigest())
        self.assertEqual(image["sourceSha"], SHA)
        self.assertEqual(image["componentSourceSha"], SHA)
        self.assertEqual(image["urls"], ["https://github.com/Audrey-cn/omarchy-android/releases/download/" +
                                         image["id"] + "/rootfs.tar.zst"])
        self.assertEqual(json.loads((self.output / "catalog.json").read_text()), {"schemaVersion": 1, "images": [image]})
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            checksum, name = line.split("  ")
            self.assertEqual(checksum, hashlib.sha256((self.output / name).read_bytes()).hexdigest())
        self.assertIn(image["sha256"] + "  rootfs.tar.zst", (self.output / "SHA256SUMS").read_text())

    def test_hardlink_and_special_entries_cannot_reach_release_metadata(self):
        for kind in (tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                self.image.unpacked_bytes(self.tar(kind))

    def test_invalid_source_identity_is_rejected(self):
        (self.output / "rootfs.tar.zst").write_bytes(b"fixture")
        with self.assertRaises(ValueError):
            self.image.write_metadata(self.output, "abcd", 42)
        self.assertFalse((self.output / "catalog.json").exists())

    def test_pinned_component_source_must_match_each_staged_receipt(self):
        root = Path(self.temp.name) / "rootfs"
        lock = REPO / "scripts/browser-bundle-sources.json"
        receipts = {
            "browser": (self.image.BROWSER_RECEIPT, "sourceSha",
                        {"sourceManifestSha256": self.image.sha256(lock)}),
            **{kind: (name, key, {}) for kind, (name, key) in self.image.COMPONENT_MANIFESTS.items()},
        }
        for name, key, properties in receipts.values():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({**properties, key: COMPONENT_SHA}))
        _, pinned_source = self.image.check_component_sources(root, SHA, COMPONENT_SHA)
        self.assertEqual(pinned_source, COMPONENT_SHA)
        with self.assertRaisesRegex(ValueError, "browser provenance"):
            self.image.check_component_sources(root, SHA, None)
        for kind, (name, key, properties) in receipts.items():
            path = root / name
            with self.subTest(component=kind):
                path.write_text(json.dumps({**properties, key: SHA}))
                with self.assertRaisesRegex(ValueError, f"{kind} provenance"):
                    self.image.check_component_sources(root, SHA, COMPONENT_SHA)
                path.write_text(json.dumps({**properties, key: COMPONENT_SHA}))

    def test_gnu_tar_materializes_hardlinks_and_preserves_symlinks(self):
        tar = shutil.which("gtar") or shutil.which("tar")
        if not tar or "GNU tar" not in subprocess.check_output([tar, "--version"], text=True):
            self.skipTest("GNU tar is required for the production image builder")
        if not shutil.which("zstd"):
            self.skipTest("zstd is required for the production image builder")
        root = Path(self.temp.name) / "rootfs"
        root.mkdir()
        (root / ".tiny.yaml").write_bytes(b"metadata")
        (root / "tool").write_bytes(b"tool")
        os.link(root / "tool", root / "another-tool")
        (root / "launcher").symlink_to("tool")
        checkout = subprocess.check_output(["git", "-C", REPO, "rev-parse", "HEAD"], text=True).strip()
        lock = REPO / "scripts/browser-bundle-sources.json"
        receipt = dict(sourceSha=COMPONENT_SHA, sourceManifestSha256=hashlib.sha256(lock.read_bytes()).hexdigest(),
                       bundleId="local-fixture", versions={})
        entries = [(self.image.BROWSER_RECEIPT, json.dumps(receipt).encode())]
        for name, key in self.image.COMPONENT_MANIFESTS.values():
            entries.append((name, json.dumps({key: COMPONENT_SHA}).encode()))
        for path in ("usr/local/bin/sway", "usr/local/bin/omarchy-node-runtime",
                     "usr/local/bin/omarchy-playwright128", "root/.config/sway/config",
                     "opt/omarchy-node/node-v24.15.0-linux-arm64/bin/node",
                     "opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/chrome",
                     "opt/omarchy-chromium128/node_modules/playwright/package.json",
                     "usr/share/doc/omarchy-image/packages.tsv", "usr/share/doc/omarchy-image/upstream-components.tsv",
                     "usr/share/doc/omarchy-image/LICENSES.md"):
            entries.append((path, b"fixture contents"))
        for name, data in entries:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        for mount in ("dev", "proc", "sys", "run", "tmp"):
            (root / mount).mkdir()
            (root / mount / "session").write_text("temporary")
            nested = "usr/include/aarch64-linux-gnu/" + mount + "/types.h"
            path = root / nested
            path.parent.mkdir(parents=True)
            path.write_bytes(b"package header")
            entries.append((nested, b"package header"))
        output = self.output / "desktop-image"
        result = subprocess.run([sys.executable, SCRIPT, "--rootfs", root, "--output-dir", output,
                                 "--source-sha", checkout, "--component-source-sha", COMPONENT_SHA],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        raw = subprocess.check_output(["zstd", "-dc", output / "rootfs.tar.zst"])
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            self.assertEqual(archive.getnames()[0], ".tiny.yaml")
            for mount in ("dev", "proc", "sys", "run", "tmp"):
                self.assertTrue(archive.getmember(mount).isdir())
                self.assertNotIn(mount + "/session", archive.getnames())
                nested = "usr/include/aarch64-linux-gnu/" + mount + "/types.h"
                self.assertEqual(archive.extractfile(nested).read(), b"package header")
            self.assertTrue(archive.getmember("another-tool").isfile())
            self.assertTrue(archive.getmember("tool").isfile())
            self.assertTrue(archive.getmember("launcher").issym())
            self.assertEqual(archive.getmember("launcher").linkname, "tool")
        image = json.loads((output / "image.json").read_text())
        source = json.loads((output / "source.json").read_text())
        self.assertEqual((image["sourceSha"], image["componentSourceSha"]), (checkout, COMPONENT_SHA))
        self.assertEqual((source["sourceSha"], source["componentSourceSha"]), (checkout, COMPONENT_SHA))
        self.assertEqual(json.loads((output / "browser-sources.json").read_text()), receipt)
        self.assertEqual(image["unpackedBytes"], 16 + sum(len(data) for _, data in entries))
        self.assertEqual(image["sha256"], hashlib.sha256((output / "rootfs.tar.zst").read_bytes()).hexdigest())
        self.assertEqual(image["bytes"], (output / "rootfs.tar.zst").stat().st_size)



if __name__ == "__main__":
    unittest.main()
