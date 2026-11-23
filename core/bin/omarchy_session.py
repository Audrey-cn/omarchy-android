#!/usr/bin/env python3
"""Identify only processes belonging to this UID, mount namespace and Sway session."""
import os
from pathlib import Path
import signal
import shutil
import subprocess
import socket


def select_sway_socket(environment, directory=Path("/tmp")):
    def active(path):
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(0.2)
                connection.connect(str(path))
            return True
        except OSError:
            return False
    inherited = environment.get("SWAYSOCK")
    if inherited:
        if active(inherited):
            return inherited
        raise RuntimeError("Inherited Sway session is no longer available")
    candidates = [str(path) for path in directory.glob("sway-ipc.*.sock") if active(path)]
    if len(candidates) != 1:
        raise RuntimeError("Choose an explicit Sway session; active sessions: " + str(len(candidates)))
    return candidates[0]


def uid(directory):
    for line in (directory / "status").read_text().splitlines():
        if line.startswith("Uid:"):
            return int(line.split()[1])
    raise ValueError("Missing process UID")


def matching_processes(names, environment, proc=Path("/proc"), current=None):
    socket = environment.get("SWAYSOCK")
    if not socket:
        return []
    current = os.getpid() if current is None else current
    own = proc / str(current)
    try:
        owner, namespace = uid(own), os.readlink(own / "ns/mnt")
    except (OSError, ValueError):
        return []
    matches = []
    for entry in proc.iterdir():
        if not entry.name.isdecimal() or int(entry.name) == current:
            continue
        try:
            if ((entry / "comm").read_text().strip() not in names or uid(entry) != owner
                    or os.readlink(entry / "ns/mnt") != namespace):
                continue
            variables = dict(item.split(b"=", 1) for item in (entry / "environ").read_bytes().split(b"\0") if b"=" in item)
            if variables.get(b"SWAYSOCK") == socket.encode():
                matches.append(int(entry.name))
        except (OSError, ValueError):
            continue
    return matches


def signal_session_processes(names, environment, signum):
    for pid in matching_processes(names, environment):
        if pid in matching_processes(names, environment):
            try:
                os.kill(pid, signum)
            except ProcessLookupError:
                pass


def stop_session_processes(names, environment):
    signal_session_processes(names, environment, signal.SIGTERM)


def mako_action(action):
    commands = {"dnd-on": ["mode", "-a", "do-not-disturb"],
                "dnd-off": ["mode", "-r", "do-not-disturb"], "reload": ["reload"]}
    if shutil.which("makoctl") and (shutil.which("busctl") or shutil.which("basuctl")):
        subprocess.run(["makoctl", *commands[action]], check=True, stdout=subprocess.DEVNULL)
        return

    # The Noble image ships Gio but omits the optional makoctl busctl backend.
    from gi.repository import Gio, GLib
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)

    def call(method, arguments=None):
        return bus.call_sync("org.freedesktop.Notifications", "/fr/emersion/Mako",
                             "fr.emersion.Mako", method, arguments, None,
                             Gio.DBusCallFlags.NO_AUTO_START, 3000, None)

    if action == "reload":
        call("Reload")
        return
    modes, = call("ListModes").unpack()
    mode = "do-not-disturb"
    if action == "dnd-on" and mode not in modes:
        modes.append(mode)
    elif action == "dnd-off" and mode in modes:
        modes = [item for item in modes if item != mode]
    else:
        return
    call("SetModes", GLib.Variant("(as)", (modes,)))


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    actions = parser.add_subparsers(dest="action", required=True)
    send = actions.add_parser("signal")
    send.add_argument("name", choices=["waybar"])
    send.add_argument("offset", type=int, choices=range(16))
    actions.add_parser("mako").add_argument("operation", choices=("dnd-on", "dnd-off", "reload"))
    args = parser.parse_args()
    if args.action == "mako":
        mako_action(args.operation)
    else:
        environment = dict(os.environ)
        environment["SWAYSOCK"] = select_sway_socket(environment)
        signal_session_processes({args.name}, environment, signal.SIGRTMIN + args.offset)
