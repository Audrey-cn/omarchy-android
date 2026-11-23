# SPDX-License-Identifier: GPL-3.0-or-later
"""Build tiny offline candidate archives; never run downloaded browser programs."""

import hashlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/build-browser-bundle.py"
loader = importlib.machinery.SourceFileLoader("browser_bundle", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
bundle = importlib.util.module_from_spec(spec)
with patch.object(sys, "dont_write_bytecode", True):
    loader.exec_module(bundle)


def fake_elf():
    return b"\x7fELF\x02\x01" + bytes(12) + (183).to_bytes(2, "little") + b"fixture binary only"


def tar_bytes(path, entries, links=None):
    with tarfile.open(path, "w:gz") as archive:
        for name, (content, mode) in entries.items():
            member = tarfile.TarInfo(name)
            member.mode, member.size = mode, len(content)
            archive.addfile(member, io.BytesIO(content))
        for name, target in (links or {}).items():
            member = tarfile.TarInfo(name)
            member.type, member.linkname, member.mode = tarfile.SYMTYPE, target, 0o777
            archive.addfile(member)


class BrowserBundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="browser candidate ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / "cache"
        self.output = self.root / "candidate"
        self.sources = json.loads(bundle.LOCK.read_text())
        self.overrides = {}
        for source in self.sources["sources"]:
            archive = self.root / source["archive"]
            self.overrides[source["id"]] = archive
            if source["id"] == "chromium":
                with zipfile.ZipFile(archive, "w") as contents:
                    for name, data, mode in [("chrome-linux/chrome", fake_elf(), 0o755),
                                             ("chrome-linux/LICENSE", b"upstream browser license", 0o644)]:
                        info = zipfile.ZipInfo(name)
                        info.external_attr = (0o100000 | mode) << 16
                        contents.writestr(info, data)
            elif source["id"] in {"playwright", "playwright-core"}:
                name = source["id"]
                package = {"name": name, "version": "1.46.1"}
                if name == "playwright":
                    package["dependencies"] = {"playwright-core": "1.46.1"}
                entries = {"package/package.json": (json.dumps(package).encode(), 0o644),
                           "package/LICENSE": (b"upstream sdk license", 0o644)}
                if name == "playwright-core":
                    entries["package/browsers.json"] = (json.dumps({"browsers": [
                        {"name": "chromium", "revision": "1129", "browserVersion": "128.0.6613.18"}
                    ]}).encode(), 0o644)
                tar_bytes(archive, entries)
                source.pop("npmIntegrity")
            else:
                prefix = "node-v24.15.0-linux-arm64/"
                tar_bytes(archive, {prefix + "bin/node": (fake_elf(), 0o755),
                                   prefix + "lib/node_modules/npm/bin/npm-cli.js": (b"npm relative imports", 0o755),
                                   prefix + "lib/node_modules/npm/bin/npx-cli.js": (b"npx relative imports", 0o755),
                                   prefix + "lib/node_modules/corepack/dist/corepack.js": (b"corepack relative imports", 0o755),
                                   prefix + "LICENSE": (b"upstream node license", 0o644)},
                          {prefix + "bin/npm": "../lib/node_modules/npm/bin/npm-cli.js",
                           prefix + "bin/npx": "../lib/node_modules/npm/bin/npx-cli.js",
                           prefix + "bin/corepack": "../lib/node_modules/corepack/dist/corepack.js"})
            source["sha256"] = bundle.digest(archive)
            source["bytes"] = archive.stat().st_size
        self.lock = self.root / "sources.json"
        self.save_lock()

    def save_lock(self):
        self.lock.write_text(json.dumps(self.sources))

    def build(self, **kwargs):
        return bundle.build(self.output, self.cache, self.lock, self.overrides, offline=True, **kwargs)

    def test_offline_build_has_verified_manifest_and_working_node_entrypoint_shims(self):
        self.build()
        manifest = json.loads((self.output / "managed-core/manifest.json").read_text())
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertRegex(manifest["sourceSha"], r"^[a-f0-9]{40}$")
        payload = self.output / "managed-core/files"
        for entry in manifest["files"]:
            path = payload / entry["path"]
            self.assertFalse(path.is_symlink())
            self.assertEqual(bundle.digest(path), entry["sha256"])
            self.assertEqual(path.stat().st_mode & 0o777, entry["mode"])
        receipt = json.loads((payload / "opt/omarchy-chromium128/source-manifest.json").read_text())
        self.assertEqual(receipt["activation"], "explicit-candidate-only")
        self.assertEqual(receipt["sourceManifestSha256"], bundle.digest(self.lock))
        self.assertTrue((payload / "opt/omarchy-chromium128/browsers/chromium-1129/chrome-linux/LICENSE").is_file())
        node_root = payload / "opt/omarchy-node/node-v24.15.0-linux-arm64"
        # Our own fixture replaces the inert ELF bytes, proving argument/path handling.
        (node_root / "bin/node").write_text("#!/usr/bin/env python3\nimport json,sys;print(json.dumps(sys.argv[1:]))\n")
        for name, relative in [("npm", "npm/bin/npm-cli.js"), ("npx", "npm/bin/npx-cli.js"),
                               ("corepack", "corepack/dist/corepack.js")]:
            result = subprocess.run([str(node_root / "bin" / name), "argument with spaces", "$(literal)"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            args = json.loads(result.stdout)
            self.assertEqual(Path(args[0]).resolve(), (node_root / "lib/node_modules" / relative).resolve())
            self.assertEqual(args[1:], ["argument with spaces", "$(literal)"])

    def test_corrupt_cache_is_rejected_offline_and_repaired_only_by_verified_bytes(self):
        source = self.sources["sources"][0]
        cached = bundle.cached_archive(source, self.cache, self.overrides["chromium"], offline=True)
        cached.write_bytes(b"corrupt cache")
        with self.assertRaisesRegex(ValueError, "offline cache"):
            bundle.cached_archive(source, self.cache, offline=True)
        self.assertFalse(cached.exists())
        repaired = bundle.cached_archive(source, self.cache, self.overrides["chromium"], offline=True)
        self.assertTrue(bundle.verified(repaired, source))
        self.overrides["chromium"].write_bytes(b"wrong local source")
        repaired.unlink()
        with self.assertRaisesRegex(ValueError, "pinned hash"):
            bundle.cached_archive(source, self.cache, self.overrides["chromium"], offline=True)
        self.assertFalse(repaired.exists())

    def test_failed_download_does_not_publish_partial_cache(self):
        source = self.sources["sources"][0]
        class BrokenResponse(io.BytesIO):
            def read(self, _size=-1):
                if self.tell():
                    raise OSError("interrupted fixture download")
                return super().read(3)
        with patch.object(bundle.urllib.request, "urlopen", side_effect=lambda *a, **kw: BrokenResponse(b"partial")):
            with self.assertRaisesRegex(ValueError, "official URL"):
                bundle.cached_archive(source, self.cache)
        self.assertFalse((self.cache / source["sha256"] / source["archive"]).exists())
        self.assertFalse(list(self.cache.rglob(".fetch-*")))

    def test_npm_integrity_is_checked_in_addition_to_archive_sha256(self):
        source = self.sources["sources"][1]
        source["npmIntegrity"] = "sha512-" + "A" * 88
        self.assertFalse(bundle.verified(self.overrides["playwright"], source))

    def test_traversal_and_escaping_symlink_never_publish_payload(self):
        for malicious in ("package/../../escape", "escaping-link"):
            with self.subTest(malicious=malicious):
                source = self.sources["sources"][1]
                archive = self.overrides["playwright"]
                if malicious == "escaping-link":
                    tar_bytes(archive, {"package/safe": (b"safe", 0o644)}, {"package/link": "../../escape"})
                else:
                    tar_bytes(archive, {malicious: (b"escape", 0o644)})
                source.update(sha256=bundle.digest(archive), bytes=archive.stat().st_size)
                self.save_lock()
                with self.assertRaisesRegex(ValueError, "Unsafe|escapes"):
                    self.build()
                self.assertFalse(self.output.exists())
                self.assertFalse((self.root / "escape").exists())

    def test_mismatched_browser_registry_is_rejected_before_publication(self):
        self.sources["versions"]["chromiumRevision"] = "9999"
        self.save_lock()
        with self.assertRaisesRegex(ValueError, "registry"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("previous reviewed candidate")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.build()
        self.assertEqual(marker.read_text(), "previous reviewed candidate")
        self.assertFalse(self.cache.exists())


if __name__ == "__main__":
    unittest.main()
