"""Source separation, stale APT, changed launchers and pre-activation Hermes checks."""

import contextlib
import hashlib
import io
import json
import runpy
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / "core/bin"
updates = runpy.run_path(str(BIN / "omarchy-updates"))
hermes = runpy.run_path(str(BIN / "omarchy-install-hermes"))
launchers = runpy.run_path(str(BIN / "omarchy-fix-desktops"))


class UpdateSourcesTests(unittest.TestCase):
    def test_refresh_failure_skips_stale_package_list_but_checks_other_sources(self):
        check = updates["check"]
        calls = []

        def failure(args):
            calls.append(args)
            raise RuntimeError("mirror failed")

        with patch.dict(
            check.__globals__,
            command=failure,
            desktop=lambda: updates["record"]("desk", "available"),
            apk=lambda: updates["record"]("apk", "current"),
            THIRD_PARTY={},
            runtime=lambda kind: updates["record"](kind, "current"),
        ):
            rows = check()
        self.assertEqual(calls, [updates["APT_REFRESH"]])
        self.assertIn("Error-Mode=any", str(calls))
        self.assertEqual(rows["apt"]["state"], "failed")
        self.assertIn("mirror failed", rows["apt"]["detail"])
        self.assertEqual(rows["desktop"]["state"], "available")

    def test_runtime_checks_are_independent_and_preserve_source_details(self):
        check = updates["check"]

        def reply(args, **kwargs):
            self.assertEqual(args[:2], [BIN / "omarchy-runtime-update", "check"])
            self.assertEqual(args[-1], "--json")
            if args[2] == "graphics":
                return subprocess.CompletedProcess(args, 1, "", "release unreachable")
            return subprocess.CompletedProcess(
                args,
                0,
                json.dumps(
                    {
                        "state": "available",
                        "installed_version": "1",
                        "available_version": "2",
                        "checked_at": "2026-11-23T06:24:00Z",
                        "sha256": "digest",
                    }
                ),
                "",
            )

        with (
            patch.dict(
                check.__globals__,
                apt=lambda: updates["record"]("apt", "current"),
                desktop=lambda: updates["record"]("desk", "current"),
                apk=lambda: updates["record"]("apk", "current"),
                THIRD_PARTY={},
            ),
            patch("subprocess.run", reply),
        ):
            rows = check()
        self.assertEqual(rows["graphics"]["state"], "failed")
        self.assertIn("release unreachable", rows["graphics"]["detail"])
        self.assertEqual(rows["browser"]["state"], "available")
        self.assertEqual(rows["browser"]["installed_version"], "1")
        self.assertEqual(rows["browser"]["available_version"], "2")
        self.assertEqual(rows["browser"]["checked_at"], "2026-11-23T06:24:00+00:00")
        self.assertIn("digest", rows["browser"]["detail"])

    def test_waybar_partial_legacy_cache_needs_check(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            updates["waybar"]({"apt": updates["record"]("apt", "current")})
        payload = json.loads(output.getvalue())
        self.assertEqual(payload["class"], "needs-check")
        self.assertEqual(payload["text"], "󰚰")
        self.assertNotEqual(payload["text"], "?")

    def test_waybar_core_paths_states_and_icons(self):
        base = {
            "apt": updates["record"]("apt", "current"),
            "desktop": updates["record"]("desktop", "current", installed="1.0.9"),
            "graphics": updates["record"]("graphics", "not-installed"),
            "browser": updates["record"]("browser", "not-installed"),
        }
        # All current -> hidden icon
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            updates["waybar"](base)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["class"], "up-to-date")
        self.assertEqual(payload["text"], "")

        # Interrupted desktop -> amber warning icon
        interrupted = dict(base, desktop=updates["record"]("desktop", "interrupted"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            updates["waybar"](interrupted)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["class"], "interrupted")
        self.assertEqual(payload["text"], "")

        # Available update -> accented update icon
        avail = dict(base, apt=updates["record"]("apt", "available", detail="foo/noble 1.0"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            updates["waybar"](avail)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["class"], "has-updates")
        self.assertEqual(payload["text"], "󰚰")

    def test_apt_failure_stops_upgrade(self):
        menu = updates["menu"]
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.dict(
                menu.__globals__,
                CACHE=Path(directory) / "cache.json",
                cached=dict,
                apt=lambda: (_ for _ in ()).throw(RuntimeError("mirror failed")),
            ),
            patch("builtins.input", return_value="2"),
            patch(
                "subprocess.run",
                side_effect=subprocess.CalledProcessError(100, "apt-get"),
            ) as proc,
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaisesRegex(RuntimeError, "mirror failed"),
        ):
            menu()
        proc.assert_not_called()

    def test_apt_candidate_must_be_backed_by_registered_source(self):
        query = updates["apt_candidate"]
        policy = """firefox:
  Candidate: 10
  Version table:
 *** 10 100
        100 /var/lib/dpkg/status
     9 500
        500 https://packages.mozilla.org/apt mozilla/main arm64 Packages"""
        with patch.dict(query.__globals__, command=lambda args: policy):
            self.assertIsNone(query("firefox"))
        with patch.dict(
            query.__globals__,
            command=lambda args: policy.replace(
                "100 /var/lib/dpkg/status",
                "500 https://packages.mozilla.org/apt mozilla/main arm64 Packages",
            ),
        ):
            self.assertEqual(query("firefox"), "10")

    def test_cached_success_expires(self):
        read = updates["cached"]
        row = updates["record"]("apt", "current")
        row["checked_at"] = (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "sources.json"
            file.write_text(json.dumps({"apt": row}))
            with patch.dict(read.__globals__, CACHE=file):
                self.assertEqual(read()["apt"]["state"], "stale")

    def test_changed_launcher_and_user_override_survive_hook(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            user = root / "custom.desktop"
            user.write_text("personal")
            launchers["publish"](root, "test", "Test", "/bin/sh", "/bin/sh")
            project = root / "omarchy-android-test.desktop"
            self.assertIn("TryExec=", project.read_text())
            project.write_text("mine")
            launchers["publish"](root, "test", "Test", "/bin/sh", "/bin/sh")
            self.assertEqual(
                (project.read_text(), user.read_text()), ("mine", "personal")
            )

    def test_unsupported_archive_never_executes_or_replaces_live_tree(self):
        install = hermes["install"]
        with tempfile.TemporaryDirectory() as directory:
            opt = Path(directory)
            live = opt / "hermes-desktop"
            live.mkdir()
            (live / "Hermes").write_bytes(b"original")
            model = opt / "profile.json"
            model.write_bytes(b"user data")

            def download(args, check):
                self.assertEqual(args[0], "curl")
                Path(args[args.index("-o") + 1]).write_bytes(b"unknown package")

            with (
                patch.dict(
                    install.__globals__, OPT=opt, REPAIR={"inspect": lambda path: None}
                ),
                patch("subprocess.run", download),
                self.assertRaisesRegex(ValueError, "SHA256"),
            ):
                install()
            self.assertEqual((live / "Hermes").read_bytes(), b"original")
            self.assertEqual(model.read_bytes(), b"user data")

    def test_unknown_existing_hermes_stops_before_any_network_or_write(self):
        install = hermes["install"]
        with tempfile.TemporaryDirectory() as directory:
            opt = Path(directory)
            live = opt / "hermes-desktop"
            live.mkdir()
            (live / "version").write_text("unreviewed")
            with (
                patch.dict(install.__globals__, OPT=opt),
                patch("subprocess.run") as call,
                self.assertRaisesRegex(RuntimeError, "Unsupported Hermes"),
            ):
                install()
            call.assert_not_called()
            self.assertEqual((live / "version").read_text(), "unreviewed")

    def test_hermes_recovery_keeps_personal_files_in_both_generations(self):
        activate, recover = hermes["activate"], hermes["recover"]

        def fixture_exchange(first, second):
            transient = first.parent / "fixture-swap"
            first.rename(transient)
            second.rename(first)
            transient.rename(second)

        with tempfile.TemporaryDirectory() as directory:
            opt = Path(directory)
            live = opt / "hermes-desktop"
            live.mkdir()
            (live / "user-extension").write_bytes(b"keep original")
            with (
                patch.dict(activate.__globals__, OPT=opt, exchange=fixture_exchange),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                with tempfile.TemporaryDirectory(dir=opt) as stage:
                    candidate = Path(stage) / "new"
                    candidate.mkdir()
                    activate(candidate, live)
                backup = opt / ".omarchy-hermes-recovery/tree"
                self.assertEqual(
                    (backup / "user-extension").read_bytes(), b"keep original"
                )
                (live / "later-edit").write_bytes(b"keep replacement")
                recover()
                self.assertEqual(
                    (live / "user-extension").read_bytes(), b"keep original"
                )
                self.assertEqual(
                    (backup / "later-edit").read_bytes(), b"keep replacement"
                )
                recover()
                self.assertTrue((live / "user-extension").exists())

    def test_unsupported_hermes_exchange_leaves_original_active_and_retryable(self):
        activate = hermes["activate"]
        with tempfile.TemporaryDirectory() as directory:
            opt = Path(directory)
            live = opt / "hermes-desktop"
            live.mkdir()
            (live / "custom").write_text("keep")
            candidate = opt / "new"
            candidate.mkdir()
            with (
                patch.dict(
                    activate.__globals__,
                    exchange=lambda *_: (_ for _ in ()).throw(OSError("unsupported")),
                ),
                self.assertRaisesRegex(OSError, "unsupported"),
            ):
                activate(candidate, live)
            self.assertEqual((live / "custom").read_text(), "keep")
            self.assertTrue(candidate.is_dir())
            self.assertFalse((opt / ".omarchy-hermes-recovery").exists())

    def test_cc_asset_api_identity_is_bound_to_release_id_and_repository(self):
        release = updates["cc_release"]
        asset = {
            "id": 42,
            "name": "CC-Switch-v1.2.3-Linux-arm64.deb",
            "browser_download_url": "https://github.com/farion1231/cc-switch/releases/download/v1.2.3/CC-Switch-v1.2.3-Linux-arm64.deb",
            "size": 4,
            "digest": "sha256:" + "0" * 64,
        }
        for resource in (
            "https://api.github.com/repos/other/cc-switch/releases/assets/42",
            "https://api.github.com/repos/farion1231/cc-switch/releases/assets/43",
        ):
            reply = json.dumps(
                {"tag_name": "v1.2.3", "assets": [{**asset, "url": resource}]}
            ).encode()
            with (
                self.subTest(resource=resource),
                patch.dict(
                    release.__globals__,
                    urlopen=lambda *args, reply=reply, **kwargs: io.BytesIO(reply),
                ),
                self.assertRaisesRegex(ValueError, "identity"),
            ):
                release()

    def test_cc_prefers_api_octet_stream_and_falls_back_only_on_transport_failure(self):
        download = updates["download_cc_asset"]
        api = "https://api.github.com/repos/farion1231/cc-switch/releases/assets/42"
        web = "https://github.com/farion1231/cc-switch/releases/download/v1.2.3/CC-Switch-v1.2.3-Linux-arm64.deb"
        content = b"test bytes"
        asset = {
            "url": api,
            "browser_download_url": web,
            "size": len(content),
            "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
        }
        for blocked in (True, False):
            calls = []

            def fetch(request, timeout, calls=calls, blocked=blocked):
                if not calls:
                    calls.append(request.full_url)
                    self.assertEqual(
                        request.get_header("Accept"), "application/octet-stream"
                    )
                    if blocked:
                        raise TimeoutError("API timeout")
                else:
                    calls.append(request)
                return io.BytesIO(content)

            with (
                self.subTest(blocked=blocked),
                tempfile.TemporaryDirectory() as temp,
                patch.dict(download.__globals__, urlopen=fetch),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                target = Path(temp) / "release.deb"
                download(asset, target)
                self.assertEqual(target.read_bytes(), content)
                self.assertEqual(calls, [api, web] if blocked else [api])

    def test_damaged_cc_asset_never_reaches_dpkg_or_apt(self):
        install = updates["install_cc"]
        release = (
            "1.2.3",
            {
                "browser_download_url": "https://example.test/a.deb",
                "url": "https://api.github.com/repos/farion1231/cc-switch/releases/assets/42",
                "size": 4,
                "digest": "sha256:" + ("0" * 64),
            },
        )
        with (
            patch.dict(
                install.__globals__,
                deb_version=lambda name: "1.2.2",
                cc_release=lambda: release,
                urlopen=lambda *a, **kw: io.BytesIO(b"fake"),
            ),
            patch(
                "subprocess.run", return_value=subprocess.CompletedProcess([], 0)
            ) as proc,
            self.assertRaisesRegex(ValueError, "integrity"),
        ):
            install()
        self.assertEqual(proc.call_count, 1)
        self.assertEqual(proc.call_args.args[0][0], "dpkg")
        self.assertIn("--compare-versions", proc.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
