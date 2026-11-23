#!/usr/bin/env python3
"""Reap only this session's children on Android kernels without PID namespaces."""
import ctypes
import os
from pathlib import Path
import signal
import sys
import time

PR_SET_PDEATHSIG = 1
PR_SET_CHILD_SUBREAPER = 36
TERM_SIGNALS = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)


def prctl(option, arg):
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(option, arg, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))


def children():
    own = str(os.getpid())
    # The task/children file requires CONFIG_PROC_CHILDREN. Android kernels
    # without it still publish PPid through proc stat.
    for entry in Path("/proc").iterdir():
        if entry.name.isdecimal():
            try:
                fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
            except (OSError, UnicodeError, IndexError):
                continue
            if fields[1] == own:
                yield int(entry.name)


def drain():
    started = time.monotonic()
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            return
        if pid:
            continue
        sig = signal.SIGTERM if time.monotonic() - started < 1 else signal.SIGKILL
        for pid in children():
            # These are our direct children. Exits remain zombies until our
            # next waitpid, so their IDs cannot be reused during this loop.
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        time.sleep(0.05)


def worker(parent, argv):
    stopping = False

    def stop(signum, frame):
        nonlocal stopping
        stopping = True

    for sig in TERM_SIGNALS:
        signal.signal(sig, stop)
    prctl(PR_SET_CHILD_SUBREAPER, 1)
    prctl(PR_SET_PDEATHSIG, signal.SIGTERM)
    if os.getppid() != parent or stopping:
        return 1
    command = os.fork()
    if command == 0:
        for sig in TERM_SIGNALS:
            signal.signal(sig, signal.SIG_DFL)
        os.setsid()
        try:
            os.execvp(argv[0], argv)
        except OSError as error:
            print(error, file=sys.stderr, flush=True)
            os._exit(127)
    try:
        while not stopping:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                return 1
            if pid == command:
                return os.waitstatus_to_exitcode(status) % 256
            if not pid:
                time.sleep(0.1)
        return 128 + signal.SIGTERM
    finally:
        drain()


def main(argv):
    if not argv:
        raise ValueError("Pass a guest command and its arguments")
    parent = os.getpid()
    child = os.fork()
    if child == 0:
        sys.exit(worker(parent, argv))

    def forward(signum, frame):
        try:
            os.kill(child, signum)
        except ProcessLookupError:
            pass

    for sig in TERM_SIGNALS:
        signal.signal(sig, forward)
    return os.waitstatus_to_exitcode(os.waitpid(child, 0)[1]) % 256


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
