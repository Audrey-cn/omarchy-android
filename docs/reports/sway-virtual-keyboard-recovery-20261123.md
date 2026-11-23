# Sway virtual-keyboard recovery on K20

## Observed failure

During the 2026-11-23 K20 acceptance run, a completed `wtype -k Tab`
invocation left Sway's keyboard state unsuitable for a new Chromium 128
Wayland client. The device operator reproduced this with both the original
and the Omarchy-specific PRoot runtime. Chromium's headless probe still
passed, so the runtime change alone did not explain the failure.

The captured Wayland protocol contained `wl_keyboard.enter` followed by
`modifiers`, with no preceding `keymap` event for the new client. The
captured native stack reached `libxkbcommon.so.0:xkb_state_update_mask`
with a null keyboard state; Chromium's signal handler then aborted.
Mesa warning messages were not sufficient evidence of the abort's cause.

The device operator's single-variable recovery tests found:

- Reapplying the physical `0:0:x11-keyboard` device's existing
  `repeat_delay 600` restored a complete stock Chromium Wayland probe.
- Switching to the already active layout index alone did not recover it.
- `events enabled` also recovered it, but could enable a device the user
  intentionally disabled, so the implementation does not use that command.

These are device-run observations reported by the main operator. The
Python tests below validate the helper's behavior against a local IPC
server; they do not substitute for the final APK/device regression.

## Recovery contract

`core/bin/omarchy-restore-x11-keyboard` uses the Sway Unix IPC protocol
directly, without Python packages or a `swaymsg` subprocess. It first tries
an existing `SWAYSOCK`, then `sway-ipc.*.sock` entries under
`XDG_RUNTIME_DIR`. A failed connection can fall back to another candidate.
No available socket or no matching physical keyboard is a quiet no-op.
An available but broken IPC endpoint is an error.

Only inputs whose name is exactly `x11-keyboard`, whose type is
`keyboard`, and whose identifier matches its explicit numeric
`vendor:product:x11-keyboard` identity are eligible. The helper snapshots
each eligible input's current `repeat_delay` and `xkb_active_layout_index`
before sending commands. It reissues that delay to trigger keyboard
configuration, then restores the saved layout index. It checks command
acknowledgements and queries the resulting delay/index again.

The helper never sends layout names, `xkb_layout`, `xkb_options`, rules,
keymap files, or `events` commands. It does not select a default language.
Success and no-op produce no stdout or stderr; a failed IPC/command/state
check produces a diagnostic on stderr and exits nonzero.

`core/bin/wtype` invokes the fixed `/usr/bin/wtype` with the original
arguments and inherited standard streams. After that process exits, its
`finally` cleanup invokes the fixed
`/usr/local/bin/omarchy-restore-x11-keyboard`. Helper stdout is suppressed;
helper failures are reported on stderr and do not replace the original
wtype exit code or signal termination status. Fixed absolute paths avoid
wrapper recursion. An explicit `/usr/bin/wtype` invocation bypasses this
wrapper, so GUI launch paths also run the helper before starting a client.

## Local behavior checks

Run:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_x11_keyboard_restore.py' -v
```

The 14 checks passed locally. The test server uses real Unix sockets and
Sway IPC framing, models a keyboard reconfiguration resetting the active
group, and checks that different original layout groups/delays and disabled
state survive recovery. It covers virtual-device exclusion, no-op paths,
stale-socket fallback, broken IPC, invalid current metadata, rejected
commands, and success acknowledgements followed by incorrect state.
Real stub processes verify raw arguments and original stdout/stderr,
ordinary failure codes, cleanup failures, a missing system binary, and
child SIGTERM/SIGKILL termination after cleanup.

Final device acceptance must reproduce `wtype -k Tab`, then start a fresh
Chromium Wayland client through the integrated GUI path and verify the
complete probe. Compare the physical keyboard's delay, active layout
index, layout names/options, and enabled state before and after the run.
The workaround reconfigures Sway's active physical keyboard; it does not
claim to patch Sway/wlroots or Chromium upstream.
