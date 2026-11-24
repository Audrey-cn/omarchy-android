"""Exercise the actual WebApp dispatcher without opening host browsers."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


LAUNCHER = Path(__file__).resolve().parents[1] / "core/bin/omarchy-launch-webapp"


@pytest.fixture
def launch_webapp(tmp_path):
    commands = tmp_path / "bin"
    commands.mkdir()
    receipt = tmp_path / "launch.json"

    def invoke(url=None, *, firefox=True, wrapper=True):
        for executable in commands.iterdir():
            executable.unlink()
        names = ["omarchy-browser"]
        if firefox:
            names.append("firefox")
        if wrapper:
            names.append("omarchy-firefox")
        for name in names:
            executable = commands / name
            executable.write_text(
                f"#!{sys.executable}\n"
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['WEBAPP_RECEIPT']).write_text("
                "json.dumps([pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]))\n"
            )
            executable.chmod(0o755)
        receipt.unlink(missing_ok=True)
        args = ["/bin/bash", str(LAUNCHER)]
        if url is not None:
            args.append(url)
        result = subprocess.run(
            args,
            env=dict(os.environ, PATH=str(commands), WEBAPP_RECEIPT=str(receipt)),
            capture_output=True,
            text=True,
            timeout=5,
        )
        assert result.returncode == 0, result.stderr
        return json.loads(receipt.read_text()) if receipt.exists() else None

    return invoke


@pytest.mark.parametrize("url", [
    "https://bilibili.com/video/BV123?x=中文&second=2",
    "https://www.bilibili.com/video/BV123",
    "HTTPS://WWW.BILIBILI.COM:443/video/BV123",
    "https://bilibili.com./video/BV123",
])
def test_bilibili_prefers_installed_mozilla_firefox(launch_webapp, url):
    assert launch_webapp(url) == ["omarchy-firefox", url]


def test_bilibili_uses_firefox_directly_without_wrapper(launch_webapp):
    url = "https://m.bilibili.com/video/BV123"
    assert launch_webapp(url, wrapper=False) == ["firefox", url]


def test_packaged_wrapper_does_not_imply_firefox_is_installed(launch_webapp):
    url = "https://www.bilibili.com/video/BV123"
    assert launch_webapp(url, firefox=False) == ["omarchy-browser", "--app=" + url]


@pytest.mark.parametrize("url", [
    "https://bilibili.com.example.test/video/BV123",
    "https://notbilibili.com/video/BV123",
    "https://bilibili.com@example.test/video/BV123",
    "https://example.test/?next=https://www.bilibili.com",
    "https://example.test/#bilibili.com",
])
def test_unrelated_hosts_keep_webapp_mode(launch_webapp, url):
    assert launch_webapp(url) == ["omarchy-browser", "--app=" + url]


def test_empty_url_does_not_launch_a_browser(launch_webapp):
    assert launch_webapp() is None
