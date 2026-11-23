"""Exercise the rootfs input used by the monorepo container build, without root."""

from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts/stage-core.sh"


class StageCoreTests(unittest.TestCase):
    def test_stages_current_checkout_from_another_working_directory(self):
        with tempfile.TemporaryDirectory(prefix="omarchy monorepo ") as temp:
            rootfs = Path(temp) / "rootfs with spaces"
            rootfs.mkdir()
            sentinel = rootfs / "existing-file"
            sentinel.write_text("keep existing rootfs content")
            result = subprocess.run(
                ["bash", str(SCRIPT), str(rootfs)], cwd=temp,
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

            sources = list((REPO / "core/bin").iterdir())
            sources += list((REPO / "core/ai-suite").glob("*launch"))
            expected = [p for p in sources if p.is_file() and not p.is_symlink()
                        and p.suffix not in {".pyc", ".pyo"} and p.read_bytes().startswith(b"#!")]
            installed = rootfs / "usr/local/bin"
            self.assertEqual({p.name for p in installed.iterdir()}, {p.name for p in expected})
            for source in expected:
                target = installed / source.name
                self.assertEqual(target.read_bytes(), source.read_bytes(), source.name)
                self.assertEqual(target.stat().st_mode & 0o777, 0o755, source.name)
            for relative in ("sway/config", "fcitx5/profile"):
                self.assertEqual(
                    (rootfs / "root/.config" / relative).read_bytes(),
                    (REPO / "core/config" / relative).read_bytes(),
                )
            self.assertEqual(sentinel.read_text(), "keep existing rootfs content")

    def test_staging_ignores_nested_junk_and_bytecode_but_keeps_script_sources(self):
        with tempfile.TemporaryDirectory(prefix="omarchy staging junk ") as temp:
            checkout = Path(temp) / "checkout"
            script = checkout / "scripts/stage-core.sh"
            script.parent.mkdir(parents=True)
            shutil.copy2(SCRIPT, script)
            commands = checkout / "core/bin"
            commands.mkdir(parents=True)
            # Repository script sources need not be +x until they are installed.
            command = commands / "omarchy-fixture"
            command.write_text("#!/usr/bin/env bash\nprintf source\n")
            command.chmod(0o644)
            for directory in [commands / "__pycache__", commands / "nested/junk"]:
                directory.mkdir(parents=True)
                (directory / "module.pyc").write_bytes(b"cached bytecode")
            bytecode = commands / "stray.pyc"
            bytecode.write_bytes(b"#!not-a-source\ncompiled bytes")
            bytecode.chmod(0o755)
            (commands / "notes.txt").write_text("not a command")
            (commands / "linked-command").symlink_to(command)
            ai = checkout / "core/ai-suite"
            ai.mkdir()
            launcher = ai / "hermes-desktop-launch"
            launcher.write_text("#!/bin/bash\nexit 0\n")
            (ai / "junk-launch").mkdir()
            for relative in ["sway/config", "fcitx5/profile"]:
                config = checkout / "core/config" / relative
                config.parent.mkdir(parents=True, exist_ok=True)
                config.write_text("configuration")
            rootfs = Path(temp) / "rootfs"
            rootfs.mkdir()
            result = subprocess.run(["bash", str(script), str(rootfs)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            installed = rootfs / "usr/local/bin"
            self.assertEqual({p.name for p in installed.iterdir()},
                             {"omarchy-fixture", "hermes-desktop-launch"})
            self.assertEqual((installed / command.name).read_bytes(), command.read_bytes())
            self.assertEqual((installed / command.name).stat().st_mode & 0o777, 0o755)
            self.assertFalse(list(rootfs.rglob("__pycache__")))
            self.assertFalse(list(rootfs.rglob("*.pyc")))

    def test_rejects_invalid_targets(self):
        with tempfile.TemporaryDirectory() as temp:
            for args in ([], ["/"], [str(Path(temp) / "missing")], [temp, temp]):
                with self.subTest(args=args):
                    result = subprocess.run(
                        ["bash", str(SCRIPT), *args], capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
