# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise Android version/signing policy without an Android SDK or private key."""

import base64
import hashlib
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tarfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/android-build.py"


class AndroidBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="omarchy android build ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Fixture")
        version = self.repo / "apps/android/version.properties"
        version.parent.mkdir(parents=True)
        version.write_text("versionCodeBase=1\nversionMajor=1\nversionMinor=0\n")
        self.git("add", ".")
        self.git("commit", "-qm", "first")
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("GITHUB_", "OMARCHY_ANDROID_"))}

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def run_script(self, *args, env=None, repo=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--repo", str(repo or self.repo), *args],
            env=env or self.env, text=True, capture_output=True,
        )

    def trusted_env(self):
        return dict(self.env, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="Audrey-cn/omarchy-android",
                    GITHUB_REF="refs/heads/main", GITHUB_EVENT_NAME="push",
                    GITHUB_ENV=str(self.root / "github.env"), GITHUB_OUTPUT=str(self.root / "github.output"))

    def mock_keytool(self):
        tools = self.root / "bin"
        tools.mkdir()
        keytool = tools / "keytool"
        keytool.write_text("#!/usr/bin/env python3\nimport sys\n"
                           "if '-exportcert' in sys.argv: sys.stdout.buffer.write(b'fixture certificate')\n"
                           "else: print('Entry type: PrivateKeyEntry')\n")
        keytool.chmod(0o755)
        return str(tools) + os.pathsep + self.env["PATH"]

    def test_version_is_reproducible_and_increases_with_ancestry(self):
        first = self.run_script("version")
        self.assertEqual(first.returncode, 0, first.stderr)
        value = json.loads(first.stdout)
        self.assertEqual(value["versionCode"], 2)
        self.assertEqual(value["versionName"], "1.0.2-test.g" + self.git("rev-parse", "--short=12", "HEAD"))
        self.assertEqual(first.stdout, self.run_script("version").stdout)
        self.git("commit", "--allow-empty", "-qm", "next")
        next_value = json.loads(self.run_script("version").stdout)
        self.assertEqual(next_value["versionCode"], 3)
        self.assertNotEqual(next_value["sourceSha"], value["sourceSha"])

    def test_shallow_history_fails_instead_of_reusing_low_version(self):
        clone = self.root / "shallow"
        subprocess.run(["git", "clone", "-q", "--depth=1", self.repo.as_uri(), str(clone)], check=True)
        result = self.run_script("version", repo=clone)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fetch-depth: 0", result.stderr)

    def test_trusted_main_requires_both_secrets(self):
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=self.trusted_env())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires", result.stderr)
        self.assertFalse((self.root / "signing/test-signing.p12").exists())

    def test_pull_request_ignores_even_present_secrets_and_marks_temporary(self):
        env = self.trusted_env()
        env.update(GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/7/merge",
                   OMARCHY_ANDROID_TEST_KEYSTORE_B64="invalid secret that must not be decoded",
                   OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD="fixture password")
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads((self.root / "signing/build-metadata.json").read_text())
        self.assertEqual(metadata["signingMode"], "temporary-pr")
        self.assertFalse(metadata["upgradeable"])
        self.assertIn("temporary", metadata["versionName"])
        self.assertIn("artifact_suffix=-temporary-pr", (self.root / "github.output").read_text())
        self.assertFalse((self.root / "signing/test-signing.p12").exists())
        self.assertNotIn("fixture password", result.stdout + result.stderr + (self.root / "github.env").read_text())

    def test_stable_key_is_private_and_password_is_not_persisted(self):
        env = self.trusted_env()
        env.update(PATH=self.mock_keytool(),
                   OMARCHY_ANDROID_TEST_KEYSTORE_B64=base64.b64encode(b"fixture keystore").decode(),
                   OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD="fixture password")
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        key = self.root / "signing/test-signing.p12"
        self.assertEqual(key.read_bytes(), b"fixture keystore")
        self.assertEqual(key.stat().st_mode & 0o777, 0o600)
        self.assertEqual(key.parent.stat().st_mode & 0o777, 0o700)
        public_files = (self.root / "github.env").read_text() + (key.parent / "build-metadata.json").read_text()
        self.assertNotIn("fixture password", public_files + result.stdout + result.stderr)
        metadata = json.loads((key.parent / "build-metadata.json").read_text())
        self.assertEqual(metadata["signingMode"], "stable-test")
        self.assertEqual(metadata["expectedCertificateSha256"], hashlib.sha256(b"fixture certificate").hexdigest())
        self.assertFalse(metadata["upgradeable"], "Only a verified APK can claim upgrade compatibility")

    def test_bad_base64_fails_without_retaining_key_or_echoing_secret(self):
        env = self.trusted_env()
        secret = "%%%not-base64%%%"
        env.update(OMARCHY_ANDROID_TEST_KEYSTORE_B64=secret,
                   OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD="fixture password")
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("encoding", result.stderr)
        self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertFalse((self.root / "signing/test-signing.p12").exists())

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL needed for local key-generation fixture")
    def test_generated_pkcs12_roundtrips_and_does_not_overwrite(self):
        output = self.root / "private signing"
        result = self.run_script("generate-keystore", "--output-dir", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        key = output / "test-signing.p12"
        password = output / "password.txt"
        encoded = output / "test-signing.keystore.b64"
        self.assertEqual(base64.b64decode(encoded.read_bytes()), key.read_bytes())
        self.assertNotIn(password.read_text().strip(), result.stdout + result.stderr)
        for private_file in (key, password, encoded):
            self.assertEqual(private_file.stat().st_mode & 0o777, 0o600)
        self.assertFalse(list(output.rglob("*.pem")))
        imported = subprocess.run(["openssl", "pkcs12", "-in", str(key), "-passin", "file:" + str(password),
                                   "-nokeys", "-noout"], capture_output=True, text=True)
        self.assertEqual(imported.returncode, 0, imported.stderr)
        java_home = self.env.get("JAVA_HOME")
        if java_home and (Path(java_home) / "bin/keytool").is_file():
            env = dict(self.env, OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD=password.read_text())
            keytool = subprocess.run([str(Path(java_home) / "bin/keytool"), "-list", "-v", "-storetype", "PKCS12",
                                      "-alias", "omarchy-android-test", "-keystore", str(key), "-storepass:env",
                                      "OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD"], env=env, capture_output=True, text=True)
            self.assertEqual(keytool.returncode, 0, "Generated PKCS12 must be readable by the configured JDK")
            self.assertIn("PrivateKeyEntry", keytool.stdout)
        again = self.run_script("generate-keystore", "--output-dir", str(output))
        self.assertNotEqual(again.returncode, 0)

    def native_fixture(self):
        names = ["libproot-omarchy-readonly.so", "libproot-omarchy-loader-aarch64.so", "libproot-omarchy-loader-arm.so"]
        manifest = dict(schemaVersion=1, runtimeId="omarchy-proot-readonly-v1", files=[])
        for role, name in zip(["proot", "loader64", "loader32"], names):
            data = ("native fixture " + name).encode()
            source = "apps/android/app/src/main/jniLibs/arm64-v8a/" + name
            target = self.repo / source
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            manifest["files"].append(dict(role=role, sourcePath=source, apkPath="lib/arm64-v8a/" + name,
                                          size=len(data), sha256=hashlib.sha256(data).hexdigest()))
        path = self.repo / "runtime/proot-omarchy/manifest.json"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(manifest))
        self.git("add", ".")
        self.git("commit", "-qm", "native runtime")
        return manifest

    def apk_fixture(self, native=False, debuggable=False):
        if native:
            self.native_fixture()
        env = self.trusted_env()
        env.update(GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/7/merge")
        output = self.root / "signing"
        prepared = self.run_script("prepare", "--output-dir", str(output), env=env)
        self.assertEqual(prepared.returncode, 0, prepared.stderr)
        metadata_path = output / "build-metadata.json"
        metadata = json.loads(metadata_path.read_text())
        tools = self.root / "build-tools"
        tools.mkdir()
        cert = "ab" * 32
        apksigner = tools / "apksigner"
        apksigner.write_text("#!/bin/sh\nprintf 'Signer #1 certificate SHA-256 digest: " + cert + "\\n'\n")
        apksigner.chmod(0o755)
        aapt = tools / "aapt"
        aapt.write_text("#!/bin/sh\nprintf \"package: name='com.omarchy.android' versionCode='"
                        + str(metadata["versionCode"]) + "' versionName='" + metadata["versionName"] + "'\\n\"\n"
                        + ("printf 'application-debuggable\\n'\n" if debuggable else ""))
        aapt.chmod(0o755)
        apk = self.root / "fixture.apk"
        self.write_apk(apk)
        args = ("verify", "--apk", str(apk), "--metadata", str(metadata_path), "--build-tools", str(tools))
        return apk, metadata_path, args

    def write_apk(self, apk, manifest=None, payloads=None, native_payloads=None):
        payloads = payloads if payloads is not None else [("usr/local/bin/omarchy-one", b"#!/bin/sh\n"),
                                                       ("opt/omarchy-node/node/__generated__/.npmrc", b"node hidden config\n")]
        if manifest is None:
            manifest = dict(schemaVersion=1, sourceSha=self.git("rev-parse", "HEAD"), files=[
                dict(path=path, sha256=hashlib.sha256(data).hexdigest(), mode=493) for path, data in payloads])
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("assets/managed-core/manifest.json", json.dumps(manifest) if isinstance(manifest, dict) else manifest)
            for path, data in payloads:
                archive.writestr("assets/managed-core/files/" + path, data)
            if native_payloads is None:
                native_manifest = self.repo / "runtime/proot-omarchy/manifest.json"
                native_payloads = [] if not native_manifest.exists() else [
                    (entry["apkPath"], (self.repo / entry["sourcePath"]).read_bytes())
                    for entry in json.loads(native_manifest.read_text())["files"]]
            for path, data in native_payloads:
                archive.writestr(path, data)

    def test_apk_certificate_and_versions_must_match_prepared_metadata(self):
        apk, metadata_path, args = self.apk_fixture()
        args += ("--require-non-debuggable",)
        result = self.run_script(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        verified = json.loads(metadata_path.read_text())
        self.assertEqual(verified["certificateSha256"], "ab" * 32)
        self.assertFalse(verified["debuggable"])
        self.assertFalse(verified["upgradeable"])
        self.assertEqual(verified["managedFileCount"], 2)
        verified.update(signingMode="stable-test", expectedCertificateSha256="ab" * 32)
        metadata_path.write_text(json.dumps(verified))
        stable = self.run_script(*args)
        self.assertEqual(stable.returncode, 0, stable.stderr)
        verified = json.loads(metadata_path.read_text())
        self.assertTrue(verified["upgradeable"])
        verified.update(signingMode="stable-test", expectedCertificateSha256="cd" * 32)
        metadata_path.write_text(json.dumps(verified))
        mismatch = self.run_script(*args)
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("certificate", mismatch.stderr)
        self.assertFalse(json.loads(metadata_path.read_text())["upgradeable"])

    def test_release_verification_rejects_debuggable_apk_and_clears_success_receipt(self):
        apk, metadata_path, args = self.apk_fixture(debuggable=True)
        metadata = json.loads(metadata_path.read_text())
        metadata.update(signingMode="stable-test", expectedCertificateSha256="ab" * 32)
        metadata_path.write_text(json.dumps(metadata))
        debug = self.run_script(*args)
        self.assertEqual(debug.returncode, 0, debug.stderr)
        debug_receipt = json.loads(metadata_path.read_text())
        self.assertIn("debuggable", debug_receipt)
        self.assertTrue(debug_receipt["debuggable"])
        rejected = self.run_script(*args, "--require-non-debuggable")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("debuggable", rejected.stderr)
        failed = json.loads(metadata_path.read_text())
        self.assertFalse(failed["upgradeable"])
        for key in ("debuggable", "certificateSha256", "delivery", "managedFileCount"):
            self.assertNotIn(key, failed)

    def test_online_installer_verifies_catalog_native_and_excludes_guest_payload(self):
        apk, metadata_path, args = self.apk_fixture(native=True)
        catalog = b'{"schemaVersion":1,"images":[]}'
        bundled = self.repo / "apps/android/app/src/main/assets/initial-images.json"
        bundled.parent.mkdir(parents=True)
        bundled.write_bytes(catalog)
        with zipfile.ZipFile(apk) as archive:
            natives = {name: archive.read(name) for name in archive.namelist() if name.startswith("lib/")}
        files = {**natives, "assets/initial-images.json": catalog,
                 "lib/arm64-v8a/libx11.so": b"\x7fELFexample Android library",
                 "assets/docs/reference.gz": gzip.compress(b"reference data"),
                 "assets/help/files.tar": tarfile.TarInfo("readme.txt").tobuf() + bytes(1024),
                 "res/raw/large_media": bytes(1024 * 1024)}
        member = tarfile.TarInfo(".tiny.yaml")
        tar_header = member.tobuf()
        # A single raw block in a standard zstd frame, with two-byte frame size.
        frame = bytes.fromhex("28b52ffd60") + (len(tar_header) - 256).to_bytes(2, "little")
        zstd_tar = frame + ((len(tar_header) << 3) | 1).to_bytes(3, "little") + tar_header
        if shutil.which("zstd"):
            files["assets/help/manual.dat"] = subprocess.check_output(
                ["zstd", "-qc"], input=files["assets/help/files.tar"])
        tar_with_guest = tarfile.TarInfo("readme").tobuf() + tar_header + bytes(1024)

        def verify_contents(entries):
            with zipfile.ZipFile(apk, "w") as archive:
                for name, payload in entries.items():
                    archive.writestr(name, payload)
            return self.run_script(*args, "--delivery", "online-installer")

        valid = verify_contents(files)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        verified = json.loads(metadata_path.read_text())
        self.assertEqual(verified["delivery"], "online-installer")
        self.assertEqual(verified["nativeLibraryCount"], 3)
        self.assertEqual(verified["managedPayloadBytes"], 0)
        self.assertEqual(verified["catalogSha256"], hashlib.sha256(catalog).hexdigest())
        cases = [
            {**files, "assets/rootfs.tar.zst": b"image"},
            {**files, "res/raw/rootfs.tar.zst": b"image"},
            {**files, "assets/desktop/usr/local/bin/omarchy-playwright128": b"command"},
            {**files, "res/raw/staging/managed-core/session": b"guest"},
            {**files, "res/raw/data.dat": b"\x7fELF" + bytes(240)},
            {**files, "lib/desktop/libnode.so": b"\x7fELF" + bytes(240)},
            {**files, "assets/misc/archive.bin": tar_header + bytes(1024)},
            {**files, "assets/misc/zstd.bin": zstd_tar},
            {**files, "assets/misc/gz.bin": gzip.compress(tar_header + bytes(1024))},
            {**files, "res/raw/doc": tar_with_guest},
            {**files, "assets/managed-core/files/opt/omarchy-node/bin/node": b"payload"},
            {**files, "assets/omarchy/../managed-core": b"ambiguous"},
            {**files, "assets/opt/omarchy-chromium128/chrome": b"browser"},
            {**files, "assets/usr/local/bin/omarchy-one": b"guest command"},
            {**files, "assets/initial-images.json": b"{}"},
            {**files, next(iter(natives)): b"replaced"},
            natives,
        ]
        for contents in cases:
            with self.subTest(modified=sorted(key for key in set(contents) | set(files) if contents.get(key) != files.get(key))):
                rejected = verify_contents(contents)
                self.assertNotEqual(rejected.returncode, 0)
                receipt = json.loads(metadata_path.read_text())
                self.assertNotIn("catalogSha256", receipt)
                self.assertFalse(receipt["upgradeable"])

    def test_gradle_source_guard_rejects_ignored_all_in_one_image_without_deleting_it(self):
        assets = self.repo / "apps/android/app/src/main/assets"
        assets.mkdir(parents=True)
        (assets / "ordinary.txt").write_text("bundled application asset")
        guard = ("check-installer-inputs",)
        valid = self.run_script(*guard)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        legacy = assets / "rootfs.tar.zst"
        (self.repo / ".gitignore").write_text("apps/android/app/src/main/assets/rootfs.tar.zst\n")
        legacy.write_bytes(b"old locally built desktop image")
        self.assertEqual(self.git("check-ignore", str(legacy)), str(legacy))
        rejected = self.run_script(*guard)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("assets/rootfs.tar.zst", rejected.stderr)
        self.assertEqual(legacy.read_bytes(), b"old locally built desktop image")
        gradle = (SCRIPT.parents[1] / "apps/android/app/build.gradle.kts").read_text()
        self.assertIn('"check-installer-inputs"', gradle)
        self.assertIn('tasks.named("preBuild")', gradle)

    def test_managed_manifest_and_every_apk_payload_are_checked_before_success(self):
        apk, metadata_path, args = self.apk_fixture()
        with zipfile.ZipFile(apk) as archive:
            original = json.loads(archive.read("assets/managed-core/manifest.json"))
        original_payloads = [("usr/local/bin/omarchy-one", b"#!/bin/sh\n"),
                             ("opt/omarchy-node/node/__generated__/.npmrc", b"node hidden config\n")]
        cases = []
        cases.append(("corrupt bytes", original, [(original_payloads[0][0], b"corrupt"), original_payloads[1]]))
        cases.append(("missing hidden asset", original, original_payloads[:1]))
        cases.append(("unlisted payload", original, original_payloads + [("usr/local/bin/unlisted", b"extra")]))
        duplicate = json.loads(json.dumps(original))
        duplicate["files"].append(duplicate["files"][0])
        cases.append(("duplicate manifest path", duplicate, original_payloads))
        traversal = json.loads(json.dumps(original))
        traversal["files"][0]["path"] = "usr/local/bin/../escape"
        cases.append(("traversal", traversal, original_payloads))
        wrong_source = dict(original, sourceSha="f" * 40)
        cases.append(("source mismatch", wrong_source, original_payloads))
        invalid_mode = json.loads(json.dumps(original))
        invalid_mode["files"][0]["mode"] = 511
        cases.append(("invalid mode", invalid_mode, original_payloads))
        invalid_type = json.loads(json.dumps(original))
        invalid_type["files"][0]["path"] = 7
        cases.append(("invalid path type", invalid_type, original_payloads))
        duplicate_json = json.dumps(original).replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1')
        cases.append(("duplicate JSON key", duplicate_json, original_payloads))
        for name, manifest, payloads in cases:
            with self.subTest(name=name):
                self.write_apk(apk, manifest, payloads)
                metadata = json.loads(metadata_path.read_text())
                metadata.update(signingMode="stable-test", expectedCertificateSha256="ab" * 32, upgradeable=True)
                metadata_path.write_text(json.dumps(metadata))
                result = self.run_script(*args)
                self.assertNotEqual(result.returncode, 0, name)
                self.assertFalse(json.loads(metadata_path.read_text())["upgradeable"], name)

    def test_duplicate_zip_member_name_is_rejected(self):
        apk, metadata_path, args = self.apk_fixture()
        with zipfile.ZipFile(apk, "a") as archive:
            with self.assertWarns(UserWarning):
                archive.writestr("assets/managed-core/files/usr/local/bin/omarchy-one", b"ambiguous")
        result = self.run_script(*args)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate", result.stderr.lower())

    def test_native_runtime_sources_are_checked_before_signing_preparation(self):
        manifest = self.native_fixture()
        source = self.repo / manifest["files"][0]["sourcePath"]
        source.write_bytes(b"tampered native source")
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=self.trusted_env())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("native", result.stderr.lower())
        self.assertFalse((self.root / "signing/test-signing.p12").exists())

    def test_apk_requires_all_three_exact_native_runtime_binaries(self):
        apk, metadata_path, args = self.apk_fixture(native=True)
        valid = self.run_script(*args)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        receipt = json.loads(metadata_path.read_text())
        self.assertEqual(receipt["nativeRuntimeId"], "omarchy-proot-readonly-v1")
        self.assertEqual(receipt["nativeLibraryCount"], 3)
        manifest = json.loads((self.repo / "runtime/proot-omarchy/manifest.json").read_text())
        payloads = [(entry["apkPath"], (self.repo / entry["sourcePath"]).read_bytes()) for entry in manifest["files"]]
        variants = [payloads[:2], [(payloads[0][0], b"stripped"), *payloads[1:]],
                    [(payloads[0][0], b"x" * len(payloads[0][1])), *payloads[1:]]]
        for payload in variants:
            with self.subTest(paths=[name for name, _ in payload]):
                self.write_apk(apk, native_payloads=payload)
                result = self.run_script(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("native", result.stderr.lower())
                failed = json.loads(metadata_path.read_text())
                self.assertFalse(failed["upgradeable"])
                self.assertNotIn("nativeLibraryCount", failed)

    def test_native_source_symlink_and_duplicate_manifest_members_are_rejected(self):
        manifest = self.native_fixture()
        source = self.repo / manifest["files"][0]["sourcePath"]
        data = source.read_bytes()
        outside = self.root / "native-outside"
        outside.write_bytes(data)
        source.unlink()
        source.symlink_to(outside)
        env = self.trusted_env()
        env.update(GITHUB_EVENT_NAME="pull_request", GITHUB_REF="refs/pull/7/merge")
        result = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("symlink", result.stderr.lower())
        source.unlink()
        source.write_bytes(data)
        manifest["files"][2] = manifest["files"][0]
        (self.repo / "runtime/proot-omarchy/manifest.json").write_text(json.dumps(manifest))
        duplicate = self.run_script("prepare", "--output-dir", str(self.root / "signing"), env=env)
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn("native", duplicate.stderr.lower())


if __name__ == "__main__":
    unittest.main()
