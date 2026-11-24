"""Download presentation, resumable transfers and opt-in mirror boundaries."""
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / "core/bin"


def load(name, filename):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / filename))
    module = importlib.util.module_from_spec(importlib.util.spec_from_loader(name, loader))
    loader.exec_module(module)
    return module


runtime = load("download_runtime", "omarchy-runtime-update")
component = load("download_component", "omarchy-component-update")
WEB = runtime.BASE + "runtime-v1.0.0/image.deb"
DATA = b"verified runtime payload"
SHA = hashlib.sha256(DATA).hexdigest()


class Response(io.BytesIO):
    def __init__(self, data, url=WEB, status=200, headers=None):
        super().__init__(data)
        self.url, self.status, self.headers = url, status, headers or {}

    def geturl(self):
        return self.url


class TTY(io.StringIO):
    def isatty(self):
        return True


class DownloadTransport(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, {}, clear=True)
        env.start()
        self.addCleanup(env.stop)
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.folder = Path(temp.name)

    def test_runtime_range_206_hashes_existing_bytes_and_appends(self):
        dest = self.folder / "image.deb"
        dest.write_bytes(DATA[:8])
        response = Response(DATA[8:], status=206,
                            headers={"Content-Range": f"bytes 8-{len(DATA)-1}/{len(DATA)}"})
        with patch.object(runtime, "urlopen", return_value=response) as opened:
            runtime.download((WEB,), len(DATA), SHA, dest)
        self.assertEqual(opened.call_args.args[0].get_header("Range"), "bytes=8-")
        self.assertEqual(dest.read_bytes(), DATA)

    def test_runtime_range_200_restarts_without_old_bytes(self):
        dest = self.folder / "image.deb"
        dest.write_bytes(b"old")
        with patch.object(runtime, "urlopen", return_value=Response(DATA)):
            runtime.download((WEB,), len(DATA), SHA, dest)
        self.assertEqual(dest.read_bytes(), DATA)

    def test_binary_file_download_requires_a_full_sha256(self):
        for checksum in (None, "", "invalid"):
            with self.subTest(checksum=checksum), patch.object(runtime, "urlopen", return_value=Response(DATA)) as opened:
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    runtime.download((WEB,), len(DATA), checksum, self.folder / "unverified.deb")
                opened.assert_not_called()

    def test_runtime_bad_range_or_digest_never_succeeds(self):
        for headers, body in (({"Content-Range": "bytes 0-3/4"}, DATA[3:]),
                              ({"Content-Range": f"bytes 3-{len(DATA)-1}/{len(DATA)}"}, b"x"*(len(DATA)-3))):
            dest = self.folder / "image.deb"
            dest.write_bytes(DATA[:3])
            with self.subTest(headers=headers, body=body), patch.object(
                    runtime, "urlopen", return_value=Response(body, status=206, headers=headers)):
                with self.assertRaises(ValueError):
                    runtime.download((WEB,), len(DATA), SHA, dest)

    def test_runtime_corrupt_mirror_falls_back_and_clears_partial(self):
        dest = self.folder / "image.deb"
        calls = []

        def source(request, timeout):
            calls.append(request.full_url)
            data = DATA if request.full_url == WEB else b"x" * len(DATA)
            return Response(data, url=request.full_url)

        with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR="https://mirror.example/download/"), \
                patch.object(runtime, "urlopen", side_effect=source):
            runtime.download((WEB,), len(DATA), SHA, dest)
        self.assertEqual(calls, ["https://mirror.example/download/" + WEB, WEB])
        self.assertEqual(dest.read_bytes(), DATA)

    def test_metadata_never_uses_mirror_and_unselected_mirror_origin_is_rejected(self):
        with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR="accel"), \
                patch.object(runtime, "urlopen", return_value=Response(DATA)) as opened:
            self.assertEqual(runtime.download((WEB,), len(DATA), SHA), DATA)
            self.assertEqual(opened.call_args.args[0].full_url, WEB)
        for module, call, target in (
                (runtime, lambda: runtime.open_source(WEB, binary=True), "urlopen"),
                (component, lambda: component.fetch(WEB, 100, binary=True), None)):
            source = patch.object(module, target, return_value=Response(DATA, "https://gh-proxy.com/"+WEB)) \
                if target else patch.object(component.urllib.request, "urlopen", return_value=Response(DATA, "https://gh-proxy.com/"+WEB))
            with source, self.assertRaisesRegex(ValueError, "origin"):
                call()

    def test_component_corrupt_mirror_uses_official_sha_and_size(self):
        web = component.DOWNLOAD + "1.0.0/desktop.deb"
        calls = []

        def fetch(url, cap, *, binary=False, progress_size=None):
            calls.append(url)
            return b"x" * len(DATA) if url.startswith("https://mirror.example/") else DATA

        with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR="https://mirror.example/"), \
                patch.object(component, "release_assets", return_value={"desktop.deb": "official-api"}), \
                patch.object(component, "fetch", side_effect=fetch):
            self.assertEqual(component.download_asset("1.0.0", "desktop.deb", len(DATA), expected_sha=SHA), DATA)
        self.assertEqual(calls, ["https://mirror.example/"+web, "official-api"])

    def test_unreachable_mirror_preserves_existing_resume_for_official_source(self):
        dest = self.folder / "image.deb"
        dest.write_bytes(DATA[:8])
        response = Response(DATA[8:], status=206,
                            headers={"Content-Range": f"bytes 8-{len(DATA)-1}/{len(DATA)}"})
        with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR="https://mirror.example/"), \
                patch.object(runtime, "urlopen", side_effect=[OSError("offline"), response]) as opened:
            runtime.download((WEB,), len(DATA), SHA, dest)
        self.assertEqual(opened.call_args_list[1].args[0].get_header("Range"), "bytes=8-")
        self.assertEqual(dest.read_bytes(), DATA)

    def test_component_catalog_does_not_show_binary_progress(self):
        with patch.object(component, "release_assets", return_value={"desktop-component.json": "api"}), \
                patch.object(component, "fetch", return_value=b"{}") as fetch:
            self.assertEqual(component.download_asset("1.0.0", "desktop-component.json", 65536), b"{}")
        self.assertNotIn("progress_size", fetch.call_args.kwargs)

    def test_open_source_and_fetch_restrict_selected_mirror_origin_to_its_binary_request(self):
        prefix = "https://mirror.example/proxy/"
        mirror = prefix + WEB
        scenarios = (
            # A selected mirror may serve its own asset or redirect to GitHub's CDN.
            (prefix, mirror, "https://mirror.example/cache/image.deb", True, True),
            (prefix, mirror, "https://release-assets.githubusercontent.com/object", True, True),
            # Selection does not authorize unrelated requests, metadata or other mirrors.
            ("direct", mirror, mirror, True, False),
            (prefix, WEB, mirror, True, False),
            (prefix, mirror, mirror, False, False),
            (prefix, mirror, "https://other-mirror.example/image.deb", True, False),
            (prefix, mirror, "http://mirror.example/image.deb", True, False),
            ("accel", "https://gh-proxy.com/" + WEB, "https://ghfast.top/" + WEB, True, False),
        )
        for updater in ("runtime", "component"):
            for selected, requested, final, binary, accepted in scenarios:
                with self.subTest(updater=updater, selected=selected, requested=requested,
                                  final=final, binary=binary):
                    response = Response(DATA, final)
                    transport = patch.object(runtime, "urlopen", return_value=response) if updater == "runtime" \
                        else patch.object(component.urllib.request, "urlopen", return_value=response)
                    with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR=selected), transport:
                        if updater == "runtime":
                            def receive():
                                with runtime.open_source(requested, binary=binary) as stream:
                                    return stream.read()
                        else:
                            def receive():
                                return component.fetch(requested, len(DATA), binary=binary)
                        if accepted:
                            self.assertEqual(receive(), DATA)
                        else:
                            with self.assertRaisesRegex(ValueError, "origin"):
                                receive()
                    self.assertTrue(response.closed)

    def test_updater_downloads_render_tty_progress_and_leave_redirected_stdout_clean(self):
        api = component.API + "/assets/123"
        for updater in ("runtime", "component"):
            with self.subTest(updater=updater):
                stderr, stdout = TTY(), io.StringIO()
                with patch("sys.stderr", stderr), patch("sys.stdout", stdout):
                    if updater == "runtime":
                        dest = self.folder / "progress.deb"
                        with patch.object(runtime, "urlopen", return_value=Response(DATA)):
                            runtime.download((WEB,), len(DATA), SHA, dest)
                        self.assertEqual(dest.read_bytes(), DATA)
                    else:
                        with patch.object(component, "release_assets", return_value={"desktop.deb": api}), \
                                patch.object(component.urllib.request, "urlopen", return_value=Response(DATA, api)):
                            self.assertEqual(component.download_asset(
                                "1.0.0", "desktop.deb", len(DATA), expected_sha=SHA), DATA)
                self.assertIn("100.0%", stderr.getvalue())
                self.assertIn("剩余", stderr.getvalue())
                self.assertTrue(stderr.getvalue().endswith("\n"))
                self.assertEqual(stdout.getvalue(), "")

    def test_real_updater_streaming_paths_are_silent_without_tty(self):
        stderr, stdout = io.StringIO(), io.StringIO()
        api = component.API + "/assets/123"
        with patch("sys.stderr", stderr), patch("sys.stdout", stdout):
            with patch.object(runtime, "urlopen", return_value=Response(DATA)):
                runtime.download((WEB,), len(DATA), SHA, self.folder / "quiet.deb")
            with patch.object(component, "release_assets", return_value={"desktop.deb": api}), \
                    patch.object(component.urllib.request, "urlopen", return_value=Response(DATA, api)):
                self.assertEqual(component.download_asset(
                    "1.0.0", "desktop.deb", len(DATA), expected_sha=SHA), DATA)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(stdout.getvalue(), "")


class ProgressAndMirrorPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = load("download_helpers", "omarchy-download.py")

    def test_format_progress(self):
        text = self.helper.format_progress(98 * 1000000, 157 * 1000000, 582000)
        self.assertIn("62.4%", text)
        self.assertIn("98.0 / 157.0 MB", text)
        self.assertIn("582 KB/s", text)
        self.assertIn("剩余 01:42", text)
        self.assertIn("█", text)
        self.assertNotIn("nan", self.helper.format_progress(0, 10, 0).lower())

    def test_progress_throttles_finishes_and_is_silent_without_tty(self):
        stream = TTY()
        clock = [0.0]
        progress = self.helper.DownloadProgress(100, stream=stream, clock=lambda: clock[0])
        progress.update(10)
        first = stream.getvalue()
        clock[0] = 0.1
        progress.update(20)
        self.assertEqual(stream.getvalue(), first)
        clock[0] = 0.21
        progress.update(30)
        self.assertNotEqual(stream.getvalue(), first)
        progress.finish(100)
        self.assertTrue(stream.getvalue().endswith("\n"))
        self.assertIn("100.0%", stream.getvalue())
        silent = io.StringIO()
        progress = self.helper.DownloadProgress(100, stream=silent)
        progress.update(20)
        progress.finish(100)
        self.assertEqual(silent.getvalue(), "")

    def test_mirror_config_defaults_env_override_and_binary_only_expansion(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=True):
            config = Path(temp) / "mirror.json"
            self.assertEqual(self.helper.mirror_prefixes(config), ())
            config.write_text(json.dumps({"mode": "accel"}))
            prefixes = self.helper.mirror_prefixes(config)
            self.assertEqual(prefixes, ("https://gh-proxy.com/", "https://ghfast.top/"))
            self.assertEqual(self.helper.expand_routes((WEB,), prefixes), tuple(p+WEB for p in prefixes)+(WEB,))
            api = "https://api.github.com/repos/a/b/releases/assets/1"
            self.assertEqual(self.helper.expand_routes((api,), prefixes), (api,))
            with patch.dict(os.environ, OMARCHY_GITHUB_MIRROR="direct"):
                self.assertEqual(self.helper.mirror_prefixes(config), ())

    def test_custom_prefix_requires_https_and_no_ambiguous_authority(self):
        for prefix in ("http://example.com/", "https:///", "https://user@example.com/", "https://example.com/?q=x", "https://example.com/#x", "https://example.com/\n"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                self.helper.validate_prefix(prefix)
        self.assertEqual(self.helper.validate_prefix("https://mirror.example/route"), "https://mirror.example/route/")

    def test_custom_prefix_rejects_empty_query_fragment_and_unusable_port(self):
        for prefix in ("https://mirror.example/?", "https://mirror.example/#", "https://mirror.example:0/"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                self.helper.validate_prefix(prefix)
