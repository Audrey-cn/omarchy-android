# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise Android version/signing policy without an Android SDK or private key."""

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
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

    def apk_fixture(self):
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
                        + str(metadata["versionCode"]) + "' versionName='" + metadata["versionName"] + "'\\n\"\n")
        aapt.chmod(0o755)
        apk = self.root / "fixture.apk"
        self.write_apk(apk)
        args = ("verify", "--apk", str(apk), "--metadata", str(metadata_path), "--build-tools", str(tools))
        return apk, metadata_path, args

    def write_apk(self, apk, manifest=None, payloads=None):
        payloads = payloads if payloads is not None else [("usr/local/bin/omarchy-one", b"#!/bin/sh\n"),
                                                       ("opt/omarchy-node/node/__generated__/.npmrc", b"node hidden config\n")]
        if manifest is None:
            manifest = dict(schemaVersion=1, sourceSha=self.git("rev-parse", "HEAD"), files=[
                dict(path=path, sha256=hashlib.sha256(data).hexdigest(), mode=493) for path, data in payloads])
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("assets/managed-core/manifest.json", json.dumps(manifest) if isinstance(manifest, dict) else manifest)
            for path, data in payloads:
                archive.writestr("assets/managed-core/files/" + path, data)

    def test_apk_certificate_and_versions_must_match_prepared_metadata(self):
        apk, metadata_path, args = self.apk_fixture()
        result = self.run_script(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        verified = json.loads(metadata_path.read_text())
        self.assertEqual(verified["certificateSha256"], "ab" * 32)
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


if __name__ == "__main__":
    unittest.main()
