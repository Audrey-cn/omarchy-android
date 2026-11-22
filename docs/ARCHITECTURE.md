# Architecture & Phase 2 Single-APK Roadmap

## 1. Why Termux Native Sway + Ubuntu Container?

Android disables SysV shared memory (`shmget`), and `Termux:X11` does not expose DRI3/DRM render nodes to standard Linux containers. Running a Wayland compositor directly inside an Ubuntu chroot fails on X11 SHM allocation.

**Solution:**
1. **Host Layer (Termux unprivileged user)**: Runs `termux-x11 :0` and Termux's native `sway 1.12` (`wlroots 0.20`, compiled with `libandroid-shmem` and `WLR_BACKENDS=x11 WLR_RENDERER=pixman`), exposing `/data/data/com.termux/files/usr/tmp/wayland-1`.
2. **Container Layer (Ubuntu 24.04 ARM64 via `chroot` or `proot`)**: Bind-mounts `$PREFIX/tmp` to `/tmp`, allowing Waybar, App Grid, GtkLayerShell popups, Alacritty, Fcitx5, CC Switch, Claude Desktop, Codex Desktop, and Hermes Desktop to talk directly to `WAYLAND_DISPLAY=wayland-1`.
3. **Pure CPU Skia Rendering for Electron/Chromium**: Passing `--disable-gpu --disable-gpu-compositing --disable-gpu-sandbox --disable-software-rasterizer` (and avoiding `--in-process-gpu`) prevents EGL/Mesa stalls and Wayland surface buffer deadlocks on `pixman` compositors.

## 2. Phase 2: Single-APK Wrapper (DroidDeck-style)

Because 85% of the stack already lives in `core/` and runs in unprivileged Termux userland, packaging into a single APK requires only a thin Android shell:

1. **Embedded Display Canvas**: Reuse Termux:X11's `LorieView` (`SurfaceView`) inside the main Activity.
2. **Prebuilt RootFS Manager**: Download a sanitized Ubuntu 24.04 + Omarchy `.tar.zst` rootfs from GitHub Releases on first launch.
3. **Runtime Selector**: Auto-detect `su` (Root `chroot` fast path) vs non-root (`proot` universal path), launching `sway` + `core/` with one tap.
