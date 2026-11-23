"""File-level recovery checks; actual Electron/PTY validation runs in the guest."""
import importlib.machinery
import importlib.util
import json
import io
import errno
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
HELPER = REPO / "core/bin/omarchy-repair-hermes-pty"


def load_helper():
    if not HELPER.exists():
        return None
    loader = importlib.machinery.SourceFileLoader("hermes_pty_repair", str(HELPER))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "dont_write_bytecode", True):
        loader.exec_module(module)
    return module


repair = load_helper()


class HermesPtyRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(repair, "Hermes native repair helper is missing")
        self.temp = tempfile.TemporaryDirectory(prefix="hermes pty ")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.target = self.base / "installed/pty.node"
        self.target.parent.mkdir()
        self.target.write_bytes(b"original incompatible binding")
        self.candidate = self.base / "rebuilt.node"
        self.candidate.write_bytes(b"rebuilt compatible binding")
        self.backup = self.base / "cache/previous.node"

    def test_validation_cleanup_preserves_original_smoke_failure(self):
        module = self.base / "bundled node-pty"
        module.mkdir()
        warning = io.StringIO()
        caught = None
        with patch.object(repair, "smoke", side_effect=RuntimeError("real PTY smoke failed")), patch.object(repair.shutil, "rmtree", side_effect=OSError(errno.ENOTEMPTY, "Directory not empty", "obj.target")), redirect_stderr(warning):
            try:
                repair.validate_candidate(Path("/fake/Hermes"), module, self.candidate, self.base)
            except Exception as error:
                caught = error
        self.assertIsInstance(caught, RuntimeError, "cleanup must not hide the native smoke error")
        self.assertEqual(str(caught), "real PTY smoke failed")

    def test_workdir_cleanup_failure_warns_after_success_and_keeps_path(self):
        self.assertTrue(hasattr(repair, "temporary_workdir"), "PRoot cleanup must not turn a successful install into a failure")
        warning = io.StringIO()
        with patch.object(repair.shutil, "rmtree", side_effect=OSError(errno.ENOTEMPTY, "Directory not empty", "obj.target")), redirect_stderr(warning):
            with repair.temporary_workdir(prefix="build-", directory=self.base) as directory:
                (directory / "obj.target").mkdir()
        self.assertTrue(directory.exists())
        self.assertIn(str(directory), warning.getvalue())
        self.assertIn("cleanup", warning.getvalue())

    def test_build_cleanup_keeps_original_compiler_error(self):
        self.assertTrue(hasattr(repair, "temporary_workdir"), "cleanup must not mask the compiler result")
        warning = io.StringIO()
        with patch.object(repair.shutil, "rmtree", side_effect=OSError(errno.ENOTEMPTY, "Directory not empty", "obj.target")), redirect_stderr(warning):
            with self.assertRaisesRegex(RuntimeError, "compiler returned 1"):
                with repair.temporary_workdir(prefix="build-", directory=self.base):
                    raise RuntimeError("compiler returned 1")

    def test_cache_write_failure_keeps_verified_install_successful(self):
        self.assertTrue(hasattr(repair, "install_and_cache"), "verified install must tolerate an unavailable optional cache")
        warning = io.StringIO()
        with patch.object(repair, "store_cache", side_effect=OSError("No space left on device")), redirect_stderr(warning):
            repair.install_and_cache(self.candidate, self.target, self.backup,
                                     lambda _: None, lambda: None, self.base / "cache", {})
        self.assertEqual(self.target.read_bytes(), self.candidate.read_bytes())
        self.assertEqual(self.backup.read_bytes(), b"original incompatible binding")
        self.assertIn("verified binding is installed", warning.getvalue())

    def test_runtime_identity_uses_installed_future_versions_and_package_js(self):
        hermes = self.base / "Hermes app"
        module = hermes / "resources/app.asar.unpacked/dist/node_modules/node-pty"
        (module / "lib").mkdir(parents=True)
        (module / "package.json").write_text(json.dumps({"name": "node-pty", "version": "1.2.3"}))
        (module / "lib/index.js").write_text("module.exports = {}")
        runtime = {"platform": "linux", "arch": "arm64", "electron": "41.2.3",
                   "node": "24.99.0", "modules": "999", "napi": "10"}
        result = SimpleNamespace(returncode=0, stdout=json.dumps(runtime), stderr="")
        with patch.object(repair.subprocess, "run", return_value=result), patch.object(repair.os, "confstr", return_value="glibc 2.39"):
            _, found, identity = repair.inspect_runtime(hermes)
            self.assertEqual(found, module)
            self.assertEqual(identity["electron"], "41.2.3")
            self.assertEqual(identity["modules"], "999")
            self.assertEqual(identity["node_pty"], "1.2.3")
            (module / "lib/index.js").write_text("module.exports = {changed: true}")
            _, _, changed = repair.inspect_runtime(hermes)
            self.assertNotEqual(repair.cache_key(identity), repair.cache_key(changed))

    def test_invalid_candidate_never_replaces_installed_binding(self):
        def reject(path):
            raise RuntimeError("native load failed")
        with self.assertRaisesRegex(RuntimeError, "native load failed"):
            repair.atomic_install(self.candidate, self.target, self.backup, reject, lambda: None)
        self.assertEqual(self.target.read_bytes(), b"original incompatible binding")
        self.assertFalse(self.backup.exists())

    def test_validated_install_keeps_one_original_for_rollback(self):
        seen = []
        repair.atomic_install(
            self.candidate, self.target, self.backup,
            lambda path: seen.append(path.read_bytes()),
            lambda: seen.append(self.target.read_bytes()),
        )
        self.assertEqual(self.target.read_bytes(), self.candidate.read_bytes())
        self.assertEqual(self.backup.read_bytes(), b"original incompatible binding")
        self.assertEqual(seen, [self.candidate.read_bytes()] * 2)
        repair.atomic_install(self.candidate, self.target, self.backup, lambda _: None, lambda: None)
        self.assertEqual(self.backup.read_bytes(), b"original incompatible binding")

    def test_failed_installed_smoke_restores_exact_previous_bytes(self):
        def reject():
            raise RuntimeError("bundled PTY spawn failed")
        with self.assertRaisesRegex(RuntimeError, "bundled PTY spawn failed"):
            repair.atomic_install(self.candidate, self.target, self.backup, lambda _: None, reject)
        self.assertEqual(self.target.read_bytes(), b"original incompatible binding")
        self.assertFalse(list(self.target.parent.glob(".omarchy-pty-*")))

    def test_failed_install_without_old_binding_removes_new_file(self):
        self.target.unlink()
        def reject():
            raise RuntimeError("spawn failed")
        with self.assertRaisesRegex(RuntimeError, "spawn failed"):
            repair.atomic_install(self.candidate, self.target, self.backup, lambda _: None, reject)
        self.assertFalse(self.target.exists())

    def test_cache_rejects_changed_runtime_or_tampered_binary(self):
        cache = self.base / "cache"
        identity = {"electron": "40.10.2", "modules": "143", "node_pty": "1.1.0", "libc": "glibc 2.39"}
        repair.store_cache(cache, self.candidate, identity)
        self.assertEqual(repair.cached_candidate(cache, identity).read_bytes(), self.candidate.read_bytes())
        changed = dict(identity, electron="41.0.0")
        self.assertIsNone(repair.cached_candidate(cache, changed))
        (cache / "pty.node").write_bytes(b"damaged cache")
        self.assertIsNone(repair.cached_candidate(cache, identity))

    def test_cache_key_changes_for_abi_libc_and_module_version(self):
        identity = {"electron": "40.10.2", "modules": "143", "node_pty": "1.1.0", "libc": "glibc 2.39"}
        for key, value in [("modules", "144"), ("libc", "glibc 2.40"), ("node_pty", "1.2.0")]:
            with self.subTest(key=key):
                self.assertNotEqual(repair.cache_key(identity), repair.cache_key(dict(identity, **{key: value})))


if __name__ == "__main__":
    unittest.main()
