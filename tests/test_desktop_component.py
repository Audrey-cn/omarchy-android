"""Exercise real release bytes and the guest update transaction in a disposable tree."""

import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]


def load(name, relative):
    loader = importlib.machinery.SourceFileLoader(name, str(REPO / relative))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


builder = load("desktop_builder", "scripts/build-desktop-component.py")
updater = load("desktop_updater", "core/bin/omarchy-component-update")


def package_files(path):
    archive = subprocess.check_output(["ar", "p", path, "data.tar.gz"])
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tf:
        contents = {}
        for item in tf:
            if item.isfile():
                assert item.name.startswith("./" + builder.PREFIX)
                contents[item.name[len(builder.PREFIX) + 2 :]] = tf.extractfile(
                    item
                ).read()
        return contents


class DesktopComponent(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        for name, rel in [
            ("PRIVATE", "usr/lib/omarchy-android/desktop"),
            ("LAUNCH", "usr/local/bin"),
            ("STATE", "var/lib/omarchy-android/desktop-updater"),
        ]:
            ctx = patch.object(updater, name, self.root / rel)
            ctx.start()
            self.addCleanup(ctx.stop)
        updater.LAUNCH.mkdir(parents=True)
        self.installed = None
        self.fail_configure = False
        for name, value in [
            ("package_state", self.pkg_status),
            ("run", self.run_dpkg),
            ("inspect_package", self.inspect_package),
        ]:
            ctx = patch.object(updater, name, value)
            ctx.start()
            self.addCleanup(ctx.stop)
        ctx = patch.object(updater.os, "geteuid", return_value=0)
        ctx.start()
        self.addCleanup(ctx.stop)
        self.cat, self.archive = self.build("1.0.0")
        self.config = self.root / "root/.config/hermes/model"
        self.config.parent.mkdir(parents=True)
        self.config.write_bytes(b"personal fixture")

    def build(self, version):
        out = self.root / version
        builder.build(version, out, "desktop menu fix")
        cat = json.loads((out / "desktop-component.json").read_text())
        return cat, out / cat["filename"]

    def pkg_status(self):
        return self.installed or (None, None)

    def inspect_package(self, path, cat):
        files = package_files(path)
        raw_manifest = files.pop("manifest.json")
        manifest = json.loads(raw_manifest)
        self.assertEqual(
            manifest["files"], {k: updater.digest(v) for k, v in files.items()}
        )
        return (
            manifest,
            json.loads(files["ownership.json"])["legacy_sha256"],
            updater.digest(raw_manifest),
        )

    def run_dpkg(self, executable, action, arg):
        self.assertEqual(executable, "dpkg")
        if action == "--install":
            files = package_files(arg)
            version = json.loads(files["manifest.json"])["version"]
            for name, data in files.items():
                path = updater.PRIVATE / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            self.installed = ("iU " if self.fail_configure else "ii ", version)
            if self.fail_configure:
                raise ValueError("simulated interruption after unpack")
        else:
            self.assertEqual(action, "--remove")
            shutil.rmtree(updater.PRIVATE)
            self.installed = None

    def install(self, cat=None, path=None):
        with patch.object(
            updater, "download_asset", return_value=(path or self.archive).read_bytes()
        ):
            updater.install(cat or self.cat)

    def test_owned_api_assets_use_binary_accept_for_explicit_version(self):
        from unittest.mock import MagicMock

        names = ["desktop-component.json", self.cat["filename"]]
        urls = [updater.API + "/assets/" + str(i) for i in (71, 72)]
        assets = [dict(name=n, id=i, url=u) for n, i, u in zip(names, (71, 72), urls)]
        tag_url = updater.API + "/tags/desktop-v1.0.0"
        release = dict(
            tag_name="desktop-v1.0.0", draft=False, prerelease=False, assets=assets
        )
        calls = []

        def serve(req, timeout):
            calls.append((req.full_url, req.get_header("Accept")))
            data = {
                tag_url: json.dumps(release).encode(),
                urls[0]: json.dumps(self.cat).encode(),
                urls[1]: b"pkg",
            }
            conn = MagicMock()
            conn.__enter__.return_value = conn
            conn.geturl.return_value = req.full_url
            conn.read.return_value = data[req.full_url]
            return conn

        updater.release_assets.cache_clear()
        self.addCleanup(updater.release_assets.cache_clear)
        with patch.object(updater.urllib.request, "urlopen", side_effect=serve):
            self.assertEqual(updater.catalog("1.0.0"), self.cat)
            self.assertEqual(updater.download_asset("1.0.0", names[1], 4), b"pkg")
            self.assertEqual(
                calls,
                [
                    (tag_url, "application/json"),
                    (urls[0], "application/octet-stream"),
                    (urls[1], "application/octet-stream"),
                ],
            )
            updater.release_assets.cache_clear()
            assets[0]["url"] = (
                "https://api.github.com/repos/untrusted/repo/releases/assets/71"
            )
            with self.assertRaisesRegex(ValueError, "asset API identity"):
                updater.catalog("1.0.0")

    def test_release_bytes_and_layout(self):
        cat2, archive2 = self.build("1.0.0")
        self.assertEqual(self.cat, cat2)
        self.assertEqual(self.cat["sha256"], updater.digest(archive2.read_bytes()))
        self.assertEqual(self.cat["size"], archive2.stat().st_size)
        files = package_files(archive2)
        self.assertIn("defaults/sway/config", files)
        self.assertIn("bin/omarchy-menu-ui", files)
        self.assertIn("LICENSE", files)
        self.assertEqual(self.cat["compatibility"]["min_apk_version_code"], 64)

    def test_pinned_ci_candidate_uses_same_transaction_offline(self):
        directory = self.archive.parent
        sha = updater.digest((directory / "desktop-component.json").read_bytes())
        with self.assertRaisesRegex(ValueError, "catalog SHA-256 mismatch"):
            updater.candidate_catalog(directory, "0" * 64, None)
        cat = updater.candidate_catalog(directory, sha, None)
        with patch.object(
            updater, "fetch", side_effect=AssertionError("unexpected network")
        ):
            updater.install(cat, directory)
        self.assertEqual(updater.status()["installed_version"], "1.0.0")

    def test_incompatible_candidate_preserves_installed_component(self):
        self.install()
        candidate, path = self.build("1.1.0")
        directory = path.parent
        sha = updater.digest((directory / "desktop-component.json").read_bytes())
        argv = [
            "update",
            "install",
            "desktop",
            "--candidate-dir",
            str(directory),
            "--catalog-sha256",
            sha,
        ]
        with (
            patch("sys.argv", argv),
            patch.object(
                updater, "check_compat", side_effect=ValueError("runtime too old")
            ),
            patch("builtins.print") as printer,
        ):
            self.assertEqual(updater.main(), 1)
            self.assertEqual(
                json.loads(printer.call_args.args[0])["state"], "incompatible"
            )
        self.assertEqual(updater.status()["installed_version"], "1.0.0")
        self.assertEqual(self.config.read_bytes(), b"personal fixture")

    def test_legacy_migration_upgrade_and_offline_recovery(self):
        old = updater.LAUNCH / "wtype"
        old.write_bytes((REPO / "core/bin/wtype").read_bytes())
        self.install()
        self.assertTrue(old.is_symlink())
        self.assertEqual(updater.status()["installed_version"], "1.0.0")
        self.install(*self.build("1.1.0"))
        updater.recover()
        self.assertEqual(updater.status()["installed_version"], "1.0.0")
        self.assertTrue(old.is_symlink())
        self.assertEqual(self.config.read_bytes(), b"personal fixture")

    def test_interrupted_first_install_restores_legacy_file(self):
        old = updater.LAUNCH / "omarchy-browser"
        old.write_bytes((REPO / "core/bin/omarchy-browser").read_bytes())
        self.fail_configure = True
        with self.assertRaisesRegex(ValueError, "interruption"):
            self.install()
        self.assertEqual(updater.status()["state"], "interrupted")
        updater.recover()
        self.assertFalse(old.is_symlink())
        self.assertEqual(
            old.read_bytes(), (REPO / "core/bin/omarchy-browser").read_bytes()
        )
        self.assertFalse(updater.PRIVATE.exists())
        self.assertEqual(self.config.read_bytes(), b"personal fixture")

    def test_pending_recovery_preserves_personal_component_edit(self):
        self.fail_configure = True
        with self.assertRaisesRegex(ValueError, "interruption"):
            self.install()
        edited = updater.PRIVATE / "bin/omarchy-menu-ui"
        edited.write_bytes(b"personal edit after interrupted installation")
        with self.assertRaisesRegex(ValueError, "Recovery component file conflict"):
            updater.recover()
        self.assertEqual(
            edited.read_bytes(), b"personal edit after interrupted installation"
        )
        self.assertEqual(updater.status()["state"], "interrupted")

    def test_user_conflicts_corrupt_download_and_recovery_conflict(self):
        old = updater.LAUNCH / "omarchy-menu-ui"
        old.write_bytes(b"personal override")
        with self.assertRaisesRegex(ValueError, "Launcher conflict"):
            self.install()
        self.assertFalse(updater.PRIVATE.exists())
        self.assertEqual(old.read_bytes(), b"personal override")
        old.unlink()
        with (
            patch.object(updater, "download_asset", return_value=b"bad"),
            self.assertRaisesRegex(ValueError, "SHA-256"),
        ):
            updater.install(self.cat)
        self.install()
        old.unlink()
        old.write_bytes(b"new override")
        with self.assertRaisesRegex(ValueError, "Recovery launcher conflict"):
            updater.recover()
        self.assertEqual(old.read_bytes(), b"new override")

    def test_incompatible_unknown_apk_and_chroot_exception(self):
        req = self.cat["compatibility"]
        with (
            patch.object(
                Path, "read_text", return_value="ID=ubuntu\nVERSION_CODENAME=noble\n"
            ),
            patch.object(updater, "run", return_value="arm64"),
        ):
            with (
                patch.dict(
                    os.environ,
                    {
                        "OMARCHY_RUNTIME_API": "1",
                        "OMARCHY_APK_VERSION_CODE": "0",
                        "OMARCHY_RUNTIME_KIND": "proot",
                    },
                ),
                self.assertRaisesRegex(ValueError, "APK_VERSION_CODE"),
            ):
                updater.check_compat(req)
            with patch.dict(
                os.environ,
                {"OMARCHY_RUNTIME_API": "1", "OMARCHY_RUNTIME_KIND": "chroot"},
            ):
                updater.check_compat(req)
            with (
                patch.dict(
                    os.environ,
                    {"OMARCHY_RUNTIME_API": "0", "OMARCHY_RUNTIME_KIND": "chroot"},
                ),
                self.assertRaisesRegex(ValueError, "RUNTIME_API"),
            ):
                updater.check_compat(req)

    @unittest.skipUnless(
        shutil.which("dpkg-deb") and shutil.which("dpkg"), "requires Debian tools"
    )
    def test_real_debian_install_upgrade_and_recover(self):
        original = load("native_updater", "core/bin/omarchy-component-update")
        system = self.root / "dpkg-root"
        (system / "var/lib/dpkg").mkdir(parents=True)
        (system / "var/lib/dpkg/status").touch()
        for name, val in [
            ("PRIVATE", system / "usr/lib/omarchy-android/desktop"),
            ("LAUNCH", system / "usr/local/bin"),
            ("STATE", system / "var/lib/updater"),
        ]:
            setattr(original, name, val)
        original.LAUNCH.mkdir(parents=True)
        native_run = original.run

        def run(*args):
            if args[0] == "dpkg":
                args = (
                    "dpkg",
                    "--force-not-root",
                    "--force-depends",
                    "--root=" + str(system),
                    *args[1:],
                )
            return native_run(*args)

        with (
            patch.object(original, "run", side_effect=run),
            patch.dict(
                os.environ,
                {"DPKG_ADMINDIR": str(system / "var/lib/dpkg"), "LC_ALL": "C"},
            ),
        ):
            with patch.object(
                original, "download_asset", return_value=self.archive.read_bytes()
            ):
                original.install(self.cat)
            cat2, path2 = self.build("1.1.0")
            with patch.object(
                original, "download_asset", return_value=path2.read_bytes()
            ):
                original.install(cat2)
            original.recover()
            self.assertEqual(original.package_state(), ("ii ", "1.0.0"))


if __name__ == "__main__":
    unittest.main()
