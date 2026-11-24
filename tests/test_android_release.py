"""Exercise the Android release workflow's shell boundaries without an SDK."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]


def workflow_command(step_name):
    lines = (ROOT / ".github/workflows/build-android.yml").read_text().splitlines()
    for index, line in enumerate(lines):
        if line.startswith("      - name: " + step_name):
            for start in range(index + 1, len(lines)):
                if lines[start] == "        run: |":
                    end = start + 1
                    while end < len(lines) and (not lines[end].strip() or lines[end].startswith("          ")):
                        end += 1
                    return textwrap.dedent("\n".join(lines[start + 1:end]))
                if lines[start].startswith("      - "):
                    break
    raise AssertionError("Missing workflow shell step: " + step_name)


class AndroidReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="omarchy-release-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.android = self.root / "apps/android"
        self.android.mkdir(parents=True)

    def run_step(self, name, env):
        result = subprocess.run(["bash", "-e", "-c", workflow_command(name)], cwd=self.android,
                                env=dict(os.environ, **env), text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_ci_builds_release_and_retains_debug_and_acceptance_test_artifacts(self):
        invocation = self.root / "gradle-arguments.json"
        (self.android / "gradlew").write_text(
            "#!/usr/bin/env python3\nimport json, pathlib, sys\n"
            "pathlib.Path(" + repr(str(invocation)) + ").write_text(json.dumps(sys.argv[1:]))\n")
        self.run_step("Assemble", {})
        self.assertEqual(json.loads(invocation.read_text()), [
            "testDebugUnitTest", "assembleRelease", "assembleDebugAndroidTest", "assembleAcceptance", "--no-daemon"])

    def test_ci_verifies_release_debuggability_and_copies_its_verified_receipt(self):
        scripts = self.root / "scripts"
        scripts.mkdir()
        invocation = self.root / "verify-arguments.json"
        (scripts / "android-build.py").write_text(
            "import json, pathlib, sys\n"
            "pathlib.Path(" + repr(str(invocation)) + ").write_text(json.dumps(sys.argv[1:]))\n")
        metadata = self.root / "metadata.json"
        metadata.write_text('{"verified":true}\n')
        output = self.android / "app/build/outputs/apk/release"
        output.mkdir(parents=True)
        (self.android / "app/build/outputs/apk/debug").mkdir()
        self.run_step("Verify online installer", {"OMARCHY_ANDROID_BUILD_METADATA": str(metadata),
                                                 "ANDROID_SDK_ROOT": "/fixture/sdk"})
        self.assertEqual(json.loads(invocation.read_text()), [
            "verify", "--delivery", "online-installer", "--require-non-debuggable",
            "--apk", "app/build/outputs/apk/release/app-release.apk", "--metadata", str(metadata),
            "--build-tools", "/fixture/sdk/build-tools/36.0.0"])
        self.assertEqual((output / "build-metadata.json").read_bytes(), metadata.read_bytes())

    def test_ndk_recipe_passes_page_alignment_to_proot_and_both_loader_links(self):
        recipe = (ROOT / "experiments/proot-readonly/build-android.sh").read_text()
        flags = recipe[recipe.index("export CFLAGS="):recipe.index("export PYTHON=")]
        loop_start = recipe.index("for variant in control patched; do", recipe.index('chmod 700 "$work_dir/ndk-cc"'))
        make_step = recipe[loop_start:recipe.index('  cp "$variant_dir/src/proot"', loop_start)] + "done\n"
        tools = self.root / "bin"
        tools.mkdir()
        report = self.root / "link-environment.json"
        make = tools / "make"
        make.write_text("#!" + sys.executable + "\nimport json, os, pathlib, sys\n"
                        "pathlib.Path(os.environ['LINK_REPORT']).write_text(json.dumps(dict(os.environ)))\n"
                        "sys.exit(87)\n")
        make.chmod(0o755)
        env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"], LINK_REPORT=str(report),
                   work_dir=str(self.root / "work"), artifact_dir=str(self.root / "artifacts"),
                   prefix=str(self.root / "prefix"), ndk_bin=str(tools), STRIP="fixture-strip")
        result = subprocess.run(["bash", "-eu", "-c", flags + make_step], env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 87, result.stderr)
        actual = json.loads(report.read_text())
        for variable in ("LDFLAGS", "LOADER_LDFLAGS", "LOADER_LDFLAGS-m32"):
            with self.subTest(variable=variable):
                self.assertIn("-Wl,-z,max-page-size=16384", actual.get(variable, "").split())
                self.assertIn("-Wl,-z,common-page-size=16384", actual.get(variable, "").split())

    def test_ndk_probe_forwards_explicit_linker_flags(self):
        compiler = self.root / "compiler"
        report = self.root / "compiler-arguments.json"
        compiler.write_text("#!" + sys.executable + "\nimport json, os, pathlib, sys\n"
                            "pathlib.Path(os.environ['LINK_REPORT']).write_text(json.dumps(sys.argv[1:]))\n"
                            "pathlib.Path(sys.argv[sys.argv.index('-o') + 1]).write_bytes(b'fixture')\n")
        compiler.chmod(0o755)
        output = self.root / "probe"
        flags = ["-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"]
        result = subprocess.run(["bash", str(ROOT / "experiments/proot-readonly/build-probe.sh"),
                                 str(output), *flags], env=dict(os.environ, CC=str(compiler), LINK_REPORT=str(report)),
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        actual = json.loads(report.read_text())
        self.assertEqual(actual[-2:], flags)


if __name__ == "__main__":
    unittest.main()
