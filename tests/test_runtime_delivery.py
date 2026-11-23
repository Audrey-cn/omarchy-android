"""Verify runtime ownership, rollback and detached process cleanup on Linux."""
import io
from contextlib import contextmanager
from importlib.machinery import SourceFileLoader
import importlib.util
import json
import lzma
import os
from pathlib import Path
import shutil
import tarfile
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch

REPO = Path(__file__).resolve().parents[1]
SUPERVISOR = REPO / "runtime/chroot/supervise.py"
loader = SourceFileLoader("runtime_update", str(REPO / "core/bin/omarchy-runtime-update"))
module = importlib.util.module_from_spec(importlib.util.spec_from_loader(loader.name, loader))
loader.exec_module(module)


def receipt_fixture(stage, legacy):
    doc = stage / "usr/share/doc/omarchy-browser-runtime"
    doc.mkdir(parents=True, exist_ok=True)
    lock = doc / "sources.json"
    shutil.copyfile(REPO / "scripts/browser-bundle-sources.json", lock)
    record = dict(json.loads(lock.read_bytes()), sourceSha="a" * 40,
                  sourceManifestSha256=module.sha(lock), transformations=[])
    candidate = stage / module.BROWSER_RECEIPT
    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.write_text(json.dumps(record))
    legacy.parent.mkdir(parents=True, exist_ok=True)
    record["sourceSha"] = "b" * 40
    legacy.write_text(json.dumps(record, indent=4) + "\n")
    return record


@contextmanager
def temporary_umask(mask):
    previous = os.umask(mask)
    try:
        yield
    finally:
        os.umask(previous)


class RuntimeContracts(unittest.TestCase):
    def test_root_display_selection_rejects_nonlocal_or_malformed_names(self):
        argv = ["sh", REPO / "runtime/chroot/ubuntu-chroot.sh", "--print-x11-display"]
        env = dict(os.environ)
        env.pop("OMARCHY_X11_DISPLAY", None)
        self.assertEqual(subprocess.check_output(argv, env=env, text=True).strip(), ":0")
        for display in (":1", ":2", ":12"):
            result = subprocess.check_output(argv, env=dict(env, OMARCHY_X11_DISPLAY=display), text=True)
            self.assertEqual(result.strip(), display)
        for display in ("", ":", "2", ":2.0", "host:2", ":2/../X1", ":2 extra", "::2", ":-2"):
            result = subprocess.run(argv, env=dict(env, OMARCHY_X11_DISPLAY=display), capture_output=True)
            self.assertEqual(result.returncode, 2, display)
            self.assertFalse(result.stdout)

    def test_root_inspection_matches_published_catalog(self):
        catalog = json.loads((REPO / "apps/android/app/src/main/assets/initial-images.json").read_text())
        receipt = dict(line.split("=", 1) for line in subprocess.check_output(
            ["sh", REPO / "runtime/chroot/install-clean.sh", "--inspect"], text=True).splitlines())
        image = next(i for i in catalog["images"] if i["id"] == receipt["image"])
        self.assertEqual(receipt["guest"], "/data/omarchy-chroot/rootfs")
        for key in ("sha256", "bytes", "unpackedBytes"):
            self.assertEqual(receipt[key], str(image[key]))

    def test_untrusted_package_paths_and_local_metadata_rejected(self):
        for path in ("usr/local/bin/sway", "/etc/passwd", "opt/omarchy-graphics/../private"):
            with self.assertRaises(ValueError):
                module.safe_path(path, "omarchy-graphics-runtime")
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            (folder / "omarchy-graphics-runtime_1.0.0_arm64.json").write_text("{}")
            with self.assertRaisesRegex(ValueError, "checksum"):
                module.local_candidate(folder, "0" * 64, "omarchy-graphics-runtime")

    def test_highest_stable_release_wins_over_recent_backport_and_preview(self):
        pkg = "omarchy-graphics-runtime"
        releases = []
        for version, preview in (("1.2.0", False), ("9.0.0", True), ("1.12.0", False)):
            stem = f"{pkg}_{version}_arm64"
            tag = "runtime-v" + version
            assets = [dict(name=stem + ext, size=2, id=i, url=f"{module.API}/assets/{i}",
                           browser_download_url=f"{module.BASE}{tag}/{stem}{ext}")
                      for i, ext in enumerate((".json", ".deb"), 1)]
            releases.append(dict(tag_name=tag, draft=False, prerelease=preview, assets=assets))
        meta = dict(asset=pkg + "_1.12.0_arm64.deb", package=pkg, version="1.12.0",
                    architecture="arm64", bytes=2, sha256="0" * 64)
        with patch.object(module, "query", return_value=releases), patch.object(module, "download", return_value=json.dumps(meta)):
            _, routes = module.candidate(pkg)
            self.assertEqual(routes[0], module.API + "/assets/2")
            self.assertIn("runtime-v1.12.0", routes[1])

    def test_asset_transport_fallback_keeps_integrity_and_rejects_other_repository(self):
        data = b"verified"
        digest = module.hashlib.sha256(data).hexdigest()
        with patch.object(module, "open_source", side_effect=[OSError("offline"), io.BytesIO(data)]) as calls:
            self.assertEqual(module.download(("api", "web"), len(data), digest), data)
            self.assertEqual([item.args[0] for item in calls.call_args_list], ["api", "web"])
        with patch.object(module, "open_source", return_value=io.BytesIO(data)) as calls:
            with self.assertRaisesRegex(ValueError, "checksum"):
                module.download(("api", "web"), len(data), "0" * 64)
            self.assertEqual(calls.call_count, 1)
        with self.assertRaisesRegex(ValueError, "ownership"):
            module.asset_routes([dict(name="file", id=2, url="elsewhere", browser_download_url="elsewhere")], "file", "runtime-v1.0.0")

    def test_deb_inspection_refuses_extra_control_entries_and_external_payload(self):
        pkg = "omarchy-graphics-runtime"
        meta = dict(version="1.0.0")
        def tar(files):
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode="w") as archive:
                for name, content in files.items():
                    info = tarfile.TarInfo("./" + name)
                    info.size = len(content)
                    archive.addfile(info, io.BytesIO(content))
            stream.seek(0)
            process = MagicMock()
            process.__enter__.return_value = process
            process.stdout = stream
            process.wait.return_value = 0
            return process
        control = {"control": f"Package: {pkg}\nVersion: 1.0.0\nArchitecture: arm64\n".encode()}
        cases = [
            ([tar(dict(control, postinst=b""))], "control member"),
            ([tar({"control": control["control"] + b"Replaces: other-package\n"})], "relationship"),
            ([tar(control), tar({"etc/example": b"example"})], "Out-of-scope"),
        ]
        for streams, reason in cases:
            with self.subTest(reason=reason), patch.object(module.subprocess, "Popen", side_effect=streams):
                with self.assertRaisesRegex(ValueError, reason):
                    module.verify_deb(Path("candidate.deb"), pkg, meta)

    def test_legacy_browser_receipt_accepts_only_matching_lock_recipe(self):
        with tempfile.TemporaryDirectory() as temp:
            stage = Path(temp) / "stage"
            legacy = Path(temp) / "old.json"
            record = receipt_fixture(stage, legacy)
            self.assertEqual(module.legacy_browser_receipt(legacy, stage), legacy.read_bytes())
            for mutate in (lambda d: d.update(sourceSha="short"),
                           lambda d: d.update(schemaVersion=True),
                           lambda d: d.update(sourceManifestSha256="0" * 64),
                           lambda d: d["versions"].update(chromium="different"),
                           lambda d: d["sources"][0].update(sha256="0" * 64),
                           lambda d: d["sources"][0].update(urls=["https://example.org"]),
                           lambda d: d.update(unexpected=True)):
                document = json.loads(json.dumps(record))
                mutate(document)
                legacy.write_text(json.dumps(document))
                with self.assertRaises(ValueError):
                    module.legacy_browser_receipt(legacy, stage)

    def test_reviewed_legacy_sway_and_browser_file_identification(self):
        for kind, name in (("graphics", "usr/local/bin/sway"),
                           ("browser", "opt/omarchy-chromium128/source-manifest.json")):
            path = REPO / f"scripts/{kind}-image-e550-baseline.json.xz"
            baseline = json.loads(lzma.decompress(path.read_bytes()))
            self.assertRegex(baseline[name], r"^[0-9a-f]{64}$")


@unittest.skipUnless(sys.platform == "linux", "Linux subreaper")
class SessionCleanup(unittest.TestCase):
    def test_killed_launcher_reaps_double_fork_and_leaves_unrelated_process(self):
        with tempfile.TemporaryDirectory() as temp:
            marker = Path(temp) / "pid"
            child = ("import os,time,sys; child=os.fork(); "
                     "child and os._exit(0); os.setsid(); child=os.fork(); "
                     "child and os._exit(0); open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(90)")
            command = [sys.executable, "-c",
                       "import subprocess,time,sys; subprocess.run(sys.argv[1:]); time.sleep(90)",
                       sys.executable, "-c", child, str(marker)]
            unrelated = subprocess.Popen(["sleep", "90"])
            process = subprocess.Popen([sys.executable, SUPERVISOR, *command])
            try:
                for _ in range(100):
                    if marker.exists() and marker.read_text():
                        break
                    time.sleep(0.02)
                detached = Path("/proc") / marker.read_text()
                self.assertTrue(detached.exists())
                process.kill()
                process.wait(timeout=5)
                for _ in range(150):
                    if not detached.exists():
                        break
                    time.sleep(0.02)
                self.assertFalse(detached.exists())
                self.assertIsNone(unrelated.poll())
            finally:
                process.kill()
                process.wait()
                unrelated.terminate()
                unrelated.wait()


@unittest.skipUnless(sys.platform == "linux" and shutil.which("dpkg-deb"), "Real Debian tools")
class DebianTransaction(unittest.TestCase):
    def test_interrupted_install_recovers_unowned_legacy_and_preserves_profile(self):
        original_run = subprocess.run
        pkg = "omarchy-browser-runtime"
        name = "opt/omarchy-chromium128/example"
        with tempfile.TemporaryDirectory() as temp:
            work = Path(temp)
            root, stage, state = (work / key for key in ("guest", "stage", "state"))
            state.mkdir()
            dpkg = root / "var/lib/dpkg"
            (dpkg / "info").mkdir(parents=True)
            (dpkg / "status").touch()
            legacy = root / name
            legacy.parent.mkdir(parents=True)
            legacy.write_bytes(b"known old runtime")
            profile = root / "root/.local/share/omarchy-chromium128/history"
            profile.parent.mkdir(parents=True)
            profile.write_text("personal")
            candidate = stage / name
            candidate.parent.mkdir(parents=True)
            candidate.write_bytes(b"new runtime")
            doc = stage / "usr/share/doc" / pkg
            doc.mkdir(parents=True)
            old_receipt = root / module.BROWSER_RECEIPT
            receipt_fixture(stage, old_receipt)
            original_receipt = old_receipt.read_bytes()
            baseline = doc / "legacy.json.xz"
            baseline.write_bytes(lzma.compress(json.dumps({name: module.sha(legacy)}).encode()))
            index = {name: module.sha(candidate), baseline.relative_to(stage).as_posix(): module.sha(baseline)}
            for extra in (stage / module.BROWSER_RECEIPT, doc / "sources.json"):
                index[extra.relative_to(stage).as_posix()] = module.sha(extra)
            (doc / "manifest.json").write_text(json.dumps(dict(package=pkg, files=index)))
            control = stage / "DEBIAN"
            control.mkdir()
            (control / "control").write_text(f"Package: {pkg}\nVersion: 1.0.0\nArchitecture: arm64\n"
                                            "Maintainer: Test <test@example.org>\nDescription: Fixture\n")
            deb = work / (pkg + "_1.0.0_arm64.deb")
            original_run(["dpkg-deb", "--root-owner-group", "--build", stage, deb], check=True)
            meta = dict(asset=deb.name, bytes=deb.stat().st_size, sha256=module.sha(deb), version="1.0.0")

            def run(args, **kwargs):
                if args[0] == "dpkg-deb" and "--build" in args:
                    snapshot = args[-2]
                    for directory, _, _ in os.walk(snapshot):
                        self.assertEqual(Path(directory).stat().st_mode & 0o777, 0o755)
                    self.assertEqual((snapshot / "DEBIAN/control").stat().st_mode & 0o777, 0o644)
                if args[0] == "dpkg":
                    args = ["dpkg", "--root=" + str(root), "--force-architecture", "--force-not-root", *args[1:]]
                return original_run(args, **kwargs)

            with temporary_umask(0), patch.object(module, "ROOT", root), patch.object(module.subprocess, "run", run), \
                    patch.object(module, "installed", return_value=None):
                legacy.write_bytes(b"local edit")
                with self.assertRaisesRegex(ValueError, "Preserved conflicting"):
                    module.install(state, pkg, "browser", meta, deb)
                self.assertEqual(legacy.read_bytes(), b"local edit")
                self.assertFalse((state / "pending.json").exists())
                legacy.write_bytes(b"known old runtime")
                with patch.object(module, "smoke", side_effect=ValueError("probe interrupted")):
                    with self.assertRaisesRegex(ValueError, "probe interrupted"):
                        module.install(state, pkg, "browser", meta, deb)
                self.assertTrue((state / "pending.json").is_file())
                self.assertEqual(legacy.read_bytes(), b"new runtime")
                tx = state / json.loads((state / "pending.json").read_text())
                changes = json.loads((tx / "changes.json").read_text())
                self.assertEqual(changes["before"][module.BROWSER_RECEIPT], module.hashlib.sha256(original_receipt).hexdigest())
                self.assertEqual(old_receipt.read_bytes(), (stage / module.BROWSER_RECEIPT).read_bytes())
                with patch.object(module, "smoke"):
                    module.recover(state, pkg, "browser")
                self.assertFalse((state / "pending.json").exists())
                self.assertEqual(legacy.read_bytes(), b"known old runtime")
                self.assertEqual(old_receipt.read_bytes(), original_receipt)
                self.assertEqual(profile.read_text(), "personal")
                self.assertNotIn("Status: install ok installed", (dpkg / "status").read_text())
