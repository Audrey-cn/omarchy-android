"""Exercise the rootfs input used by the monorepo container build, without root."""

from pathlib import Path
import subprocess
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

            expected = list((REPO / "core/bin").iterdir())
            expected += list((REPO / "core/ai-suite").glob("*launch"))
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
