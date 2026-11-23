"""The runtime installer must verify bytes before publishing a usable path."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "core/bin/omarchy-node-runtime"
PACKAGE = "node-v24.15.0-linux-arm64"


class NodeRuntimeTests(unittest.TestCase):
    def fixture(self, root, corrupt=False):
        commands = root / "commands"
        commands.mkdir()
        source = root / "source" / PACKAGE / "bin"
        source.mkdir(parents=True)
        node = source / "node"
        node.write_text('#!/bin/sh\necho v24.15.0\n')
        node.chmod(0o755)
        archive = root / (PACKAGE + ".tar.xz")
        with tarfile.open(archive, "w:xz") as tar:
            tar.add(source.parent, arcname=PACKAGE)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if corrupt:
            digest = "0" * 64
        manifest = root / "SHASUMS256.txt"
        manifest.write_text(digest + "  " + archive.name + "\n")
        fixtures = {
            "uname": '#!/bin/sh\ncase "$1" in -s) echo Linux;; -m) echo aarch64;; esac\n',
            "getconf": '#!/bin/sh\necho "glibc 2.39"\n',
            "flock": '#!/bin/sh\nexit 0\n',
            "curl": '#!/bin/sh\nfor arg do case "$arg" in https://*/SHASUMS256.txt) source="$FIXTURE/SHASUMS256.txt";; https://*tar.xz) source="$FIXTURE/' + archive.name + '";; esac; done\nwhile [ "$1" != -o ]; do shift; done\ncp "$source" "$2"\n',
        }
        if not shutil.which("xz"):
            # BSD tar reads xz itself; GNU tar on Linux uses the real xz executable.
            fixtures["xz"] = '#!/bin/sh\nexit 0\n'
        # macOS has shasum; Linux CI has sha256sum.
        if not Path("/usr/bin/sha256sum").exists():
            fixtures["sha256sum"] = '#!/bin/sh\nexec shasum -a 256 "$@"\n'
        for name, content in fixtures.items():
            path = commands / name
            path.write_text(content)
            path.chmod(0o755)
        return dict(os.environ, PATH=str(commands) + ":" + os.environ["PATH"],
                    FIXTURE=str(root), OMARCHY_NODE_HOME=str(root / "managed runtime"))

    def test_verified_install_and_cached_stdout_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = self.fixture(root)
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            expected = root / "managed runtime" / PACKAGE / "bin/node"
            self.assertEqual(result.stdout, str(expected.resolve()) + "\n")
            self.assertTrue(expected.is_file())
            # A cached valid runtime must not fetch or need installer-only tools.
            (root / "commands/curl").write_text('#!/bin/sh\nexit 91\n')
            cached = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(cached.returncode, 0, cached.stderr)
            self.assertEqual(cached.stdout, result.stdout)
            self.assertEqual(cached.stderr, "")

    def test_bad_checksum_never_publishes_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = self.fixture(root, corrupt=True)
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertFalse((root / "managed runtime" / PACKAGE).exists())


if __name__ == "__main__":
    unittest.main()
