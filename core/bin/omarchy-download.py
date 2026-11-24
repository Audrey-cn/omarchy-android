#!/usr/bin/env python3
"""Shared terminal progress and explicitly selected GitHub binary mirror policy."""
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit

MIRROR_CONFIG = Path("/root/.config/omarchy/download-mirror.json")
ACCEL_PREFIXES = ("https://gh-proxy.com/", "https://ghfast.top/")
OFFICIAL_HOSTS = frozenset(("api.github.com", "github.com",
                           "release-assets.githubusercontent.com", "objects.githubusercontent.com"))


def validate_prefix(prefix):
    if not isinstance(prefix, str) or any(c.isspace() or ord(c) < 32 for c in prefix) or "\\" in prefix:
        raise ValueError("Mirror prefix must be an https:// URL")
    parts = urlsplit(prefix)
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or "?" in prefix or "#" in prefix):
        raise ValueError("Mirror prefix must be an https:// URL without credentials, query or fragment")
    # Accessing port also rejects malformed/out-of-range port numbers.
    if parts.port == 0:
        raise ValueError("Mirror prefix port must be between 1 and 65535")
    return prefix.rstrip("/") + "/"


def mirror_prefixes(config=MIRROR_CONFIG):
    override = os.environ.get("OMARCHY_GITHUB_MIRROR")
    if override is not None:
        mode = override or "direct"
        custom = override
        if mode.startswith("https://"):
            mode = "custom"
    else:
        try:
            data = json.loads(config.read_text())
            mode, custom = data.get("mode", "direct"), data.get("custom_prefix", "")
        except FileNotFoundError:
            return ()
        except (OSError, ValueError, AttributeError) as error:
            raise ValueError("Invalid download mirror configuration") from error
    if mode == "direct":
        return ()
    if mode == "accel":
        return ACCEL_PREFIXES
    if mode == "custom":
        return (validate_prefix(custom),)
    raise ValueError("Unknown download mirror mode")


def release_link(url):
    parts = urlsplit(url)
    return (parts.scheme == "https" and parts.netloc == "github.com"
            and bool(re.fullmatch(r"/[^/]+/[^/]+/releases/download/[^/]+/[^/]+", parts.path))
            and not parts.query and not parts.fragment)


def expand_routes(routes, prefixes):
    routes = tuple(routes)
    mirrors = [prefix + url for prefix in prefixes for url in routes if release_link(url)]
    return tuple(dict.fromkeys((*mirrors, *routes)))


def is_mirror_route(url, prefixes):
    return any(url.startswith(prefix) and release_link(url[len(prefix):]) for prefix in prefixes)


def allowed_origin(url, final_url, binary=False):
    final = urlsplit(final_url)
    allowed = set(OFFICIAL_HOSTS)
    # Selecting a mirror only grants that host access for its own binary requests.
    if binary:
        prefixes = mirror_prefixes()
        allowed.update(urlsplit(prefix).hostname for prefix in prefixes
                       if url.startswith(prefix) and release_link(url[len(prefix):]))
    return final.scheme == "https" and final.hostname in allowed


def format_progress(done, total, speed, width=20):
    fraction = min(1.0, max(0.0, done / total)) if total else 0.0
    filled = int(width * fraction)
    seconds = math.ceil(max(0, total - done) / speed) if speed > 0 else None
    eta = "--:--" if seconds is None else f"{seconds // 60:02d}:{seconds % 60:02d}"
    return (f"[{'█' * filled}{'░' * (width - filled)}] {fraction * 100:5.1f}%   "
            f"{done / 1000000:.1f} / {total / 1000000:.1f} MB   "
            f"{speed / 1000:.0f} KB/s   剩余 {eta}")


class DownloadProgress:
    def __init__(self, total, initial=0, stream=None, clock=time.monotonic):
        self.stream = stream if stream is not None else (
            sys.stderr if sys.stderr.isatty() else sys.stdout if sys.stdout.isatty() else None)
        self.enabled = self.stream is not None and self.stream.isatty()
        self.total, self.initial, self.clock = total, initial, clock
        self.started, self.last = clock(), None

    def update(self, done, force=False):
        if not self.enabled:
            return
        now = self.clock()
        if not force and self.last is not None and now - self.last < 0.2:
            return
        elapsed = now - self.started
        speed = max(0, done - self.initial) / elapsed if elapsed > 0 else 0
        self.stream.write("\r" + format_progress(done, self.total, speed))
        self.stream.flush()
        self.last = now

    def finish(self, done):
        if self.enabled:
            self.update(done, force=True)
            self.stream.write("\n")
            self.stream.flush()
