"""Exercise the actual WebApp dispatcher without opening host browsers."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

LAUNCHER = Path(__file__).resolve().parents[1] / "core/bin/omarchy-launch-webapp"


class WebappFallbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.commands = Path(self.temp.name) / "bin"
        self.commands.mkdir()
        self.receipt = Path(self.temp.name) / "launch.json"

    def invoke(self, url=None, *, firefox=True, wrapper=True, mode=None):
        for executable in self.commands.iterdir():
            executable.unlink()
        names = ["omarchy-browser"]
        if firefox:
            names.append("firefox")
        if wrapper:
            names.append("omarchy-firefox")
        for name in names:
            executable = self.commands / name
            executable.write_text(
                f"#!{sys.executable}\n"
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['WEBAPP_RECEIPT']).write_text("
                "json.dumps([pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]))\n"
            )
            executable.chmod(0o755)
        self.receipt.unlink(missing_ok=True)
        args = ["/bin/bash", str(LAUNCHER)]
        if mode is not None:
            args.append(mode)
        if url is not None:
            args.append(url)
        result = subprocess.run(
            args,
            env=dict(os.environ, PATH=str(self.commands), WEBAPP_RECEIPT=str(self.receipt)),
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(self.receipt.read_text()) if self.receipt.exists() else None

    def test_video_sites_prefer_installed_mozilla_firefox(self):
        for url in [
            "https://bilibili.com/video/BV123?x=中文&second=2",
            "https://www.bilibili.com/video/BV123",
            "HTTPS://WWW.BILIBILI.COM:443/video/BV123",
            "https://bilibili.com./video/BV123",
            "https://www.douyin.com/video/123",
            "https://www.iqiyi.com/v_123.html",
            "https://v.qq.com/x/cover/123.html",
            "https://www.youku.com/v_show/id_123.html",
            "https://www.twitch.tv/videos/123",
        ]:
            with self.subTest(url=url):
                self.assertEqual(self.invoke(url), ["omarchy-firefox", url])

    def test_bilibili_uses_firefox_directly_without_wrapper(self):
        url = "https://m.bilibili.com/video/BV123"
        self.assertEqual(self.invoke(url, wrapper=False), ["firefox", url])

    def test_packaged_wrapper_does_not_imply_firefox_is_installed(self):
        url = "https://www.bilibili.com/video/BV123"
        self.assertEqual(self.invoke(url, firefox=False), ["omarchy-browser", url])

    def test_explicit_app_and_tab_modes_override_site_default(self):
        video = "https://www.bilibili.com/video/BV123?one=1&two=2"
        tool = "https://chat.deepseek.com/"
        self.assertEqual(self.invoke(video, mode="--app"), ["omarchy-browser", "--app=" + video])
        self.assertEqual(self.invoke(tool, mode="--tab"), ["omarchy-firefox", tool])
        self.assertEqual(self.invoke(tool, mode="--tab", firefox=False), ["omarchy-browser", tool])

    def test_unrelated_hosts_keep_webapp_mode(self):
        for url in [
            "https://bilibili.com.example.test/video/BV123",
            "https://notbilibili.com/video/BV123",
            "https://bilibili.com@example.test/video/BV123",
            "https://example.test/?next=https://www.bilibili.com",
            "https://example.test/#bilibili.com",
        ]:
            with self.subTest(url=url):
                self.assertEqual(self.invoke(url), ["omarchy-browser", "--app=" + url])

    def test_empty_url_does_not_launch_a_browser(self):
        self.assertIsNone(self.invoke())


if __name__ == "__main__":
    unittest.main()
