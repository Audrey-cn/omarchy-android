"""Desktop state and launch behavior, without a compositor or GTK installation."""

import builtins
from contextlib import ExitStack
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, relative):
    loader = importlib.machinery.SourceFileLoader(name, str(ROOT / relative))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, {
        "gi": SimpleNamespace(require_version=lambda *_: None),
        "gi.repository": SimpleNamespace(Gtk=SimpleNamespace(Window=object), Gdk=None,
            GdkPixbuf=None, Pango=None, GLib=None, GtkLayerShell=None),
    }):
        loader.exec_module(module)
    return module


class ThemeStateTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.engine = load("polish_theme", "core/bin/omarchy-theme-engine.py")
        self.panel = load("polish_panel", "core/bin/omarchy-panel")
        self.grid = load("polish_grid", "core/bin/omarchy-app-grid")
        self.state = self.root / "root/.local/state/omarchy/current"
        self.themes = self.root / "root/.local/share/omarchy/themes"
        self.themes.mkdir(parents=True)
        for name, background in [("tokyo-night", "#111122"), ("custom", "#123456")]:
            folder = self.themes / name
            (folder / "backgrounds").mkdir(parents=True)
            (folder / "colors.toml").write_text(
                f'background = "{background}"\nforeground = "#abcdef"\naccent = "#aa4488"\ncolor8 = "#334455"\n')
            for filename in ("first.jpg", "selected.jpg"):
                (folder / "backgrounds" / filename).write_bytes(b"fixture image")
        self.calls = []
        real_open = builtins.open
        real_exists, real_isdir = os.path.exists, os.path.isdir
        real_makedirs, real_glob = os.makedirs, self.engine.glob.glob

        def mapped(path):
            value = os.fspath(path)
            if value.startswith("/root/") or value == "/tmp/omarchy-sway.conf":
                return str(self.root / value.lstrip("/"))
            return value

        self.mapped = mapped
        context = ExitStack()
        self.addCleanup(context.close)
        context.enter_context(patch("builtins.open", side_effect=lambda p, *a, **k: real_open(mapped(p), *a, **k)))
        context.enter_context(patch.object(os.path, "exists", side_effect=lambda p: real_exists(mapped(p))))
        context.enter_context(patch.object(os.path, "isdir", side_effect=lambda p: real_isdir(mapped(p))))
        context.enter_context(patch.object(os, "makedirs", side_effect=lambda p, *a, **k: real_makedirs(mapped(p), *a, **k)))
        context.enter_context(patch.object(self.engine.glob, "glob", side_effect=lambda p: real_glob(mapped(p))))
        context.enter_context(patch.object(self.engine.subprocess, "check_output", return_value="Screen 0: current 1080 x 2400, maximum 8192 x 8192"))
        context.enter_context(patch.object(self.engine.subprocess, "run", side_effect=lambda args, **kw: self.calls.append(args) or SimpleNamespace(returncode=0)))
        context.enter_context(patch.object(self.engine.subprocess, "Popen"))
        context.enter_context(patch.object(self.engine, "wl_env", return_value={"SWAYSOCK": "fixture"}))
        context.enter_context(patch.dict(sys.modules, {"omarchy_session": SimpleNamespace(
            stop_session_processes=lambda *_: None, select_sway_socket=lambda _: "fixture")}))
        (self.root / "tmp").mkdir()

    def seed_saved(self, name="custom", background=None):
        self.state.mkdir(parents=True, exist_ok=True)
        (self.state / "theme.name").write_text(name)
        if background:
            (self.state / "background.path").write_text(str(background))

    def test_restore_saved_theme_and_wallpaper_publishes_colors_for_both_consumers(self):
        wallpaper = self.themes / "custom/backgrounds/selected.jpg"
        self.seed_saved(background=wallpaper)
        self.assertTrue(hasattr(self.engine, "restore_theme"), "missing persisted theme restoration")
        self.assertTrue(self.engine.restore_theme())
        self.assertEqual((self.state / "theme.name").read_text(), "custom")
        self.assertEqual((self.state / "background.path").read_text(), str(wallpaper))
        self.assertEqual(self.panel.load_theme(), ("#123456", "#abcdef", "#aa4488"))
        self.assertEqual(self.grid.load_theme_colors(), ("#123456", "#abcdef", "#aa4488", "#334455"))
        self.assertIn(["swaymsg", "reload"], self.calls)
        config = (self.root / "root/.config/sway/config").read_text()
        self.assertIn("output X11-1 mode 1080x2400 scale 1.5", config)

    def test_restore_first_boot_uses_tokyo_night_and_invalid_wallpaper_uses_theme_default(self):
        self.assertTrue(hasattr(self.engine, "restore_theme"), "missing persisted theme restoration")
        self.assertTrue(self.engine.restore_theme())
        self.assertEqual((self.state / "theme.name").read_text(), "tokyo-night")
        self.seed_saved(background=self.root / "missing.jpg")
        self.assertTrue(self.engine.restore_theme())
        self.assertEqual((self.state / "background.path").read_text(), str(self.themes / "custom/backgrounds/first.jpg"))

    def test_switch_modifier_regenerates_complete_binding_set_and_reloads(self):
        self.seed_saved()
        modifier = self.root / "root/.config/omarchy/modifier.json"
        for mode in ("super+alt", "alt", "super", "super+alt"):
            with self.subTest(mode=mode):
                with patch.object(self.panel.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as restore:
                    self.panel.OmarchyPanel.save_mod_cfg(None, {"mode": mode, "caps_as_super": False})
                    self.assertTrue(restore.called, "panel must regenerate all bindings with the theme engine")
                    self.assertEqual(restore.call_args.args[0], ["/usr/local/bin/omarchy-theme-engine.py", "restore"])
                self.assertEqual(json.loads(modifier.read_text())["mode"], mode)
                self.assertTrue(self.engine.restore_theme())
                config = (self.root / "root/.config/sway/config").read_text()
                self.assertIn("set $mod " + ("Mod1" if mode == "alt" else "Mod4"), config)
                self.assertEqual("bindsym $alt+Return exec alacritty" in config, mode == "super+alt")
                resolved = config.replace("$mod", "Mod1" if mode == "alt" else "Mod4").replace("$alt", "Mod1")
                keys = re.findall(r"^bindsym (\S+)", resolved, re.M)
                self.assertEqual(len(keys), len(set(keys)), "duplicate effective bindings")
                self.assertFalse(any("Mod1+Mod1" in key for key in keys))

    def test_boot_media_uses_saved_theme_restoration(self):
        template = (ROOT / "apps/android/container/omarchy.tiny.yaml").read_text()
        self.assertIn("omarchy-theme-engine.py restore", template)
        self.assertNotIn("omarchy-theme-engine.py set tokyo-night", template)
        workflow = (ROOT / ".github/workflows/build-container.yml").read_text()
        self.assertIn('cp apps/android/container/omarchy.tiny.yaml "$ROOTFS/.tiny.yaml"', workflow)
        self.assertIn("fcitx5-config-qt", workflow)


class DesktopLaunchTests(unittest.TestCase):
    def test_grid_exec_launch_preserves_url_metacharacters_without_a_shell(self):
        grid = load("polish_grid_launch", "core/bin/omarchy-app-grid")
        url = 'https://example.test/?one=1&two="quoted value"'
        command = "/usr/local/bin/omarchy-launch-webapp " + shlex.quote(url) + " %u %U %f %F"
        with patch.dict(sys.modules, {"omarchy_session": SimpleNamespace(select_sway_socket=lambda _: "fixture")}), \
             patch.object(grid.subprocess, "check_output", return_value='{"nodes": []}'), \
             patch.object(grid.subprocess, "Popen") as launch, \
             patch("builtins.open", side_effect=OSError("no log")):
            grid.launch_app({"name": "Fixture", "exec": command, "terminal": False})
            self.assertEqual(launch.call_args.args[0], ["/usr/local/bin/omarchy-launch-webapp", url])
            grid.launch_app({"name": "Fixture", "exec": 'tool "two words" %u', "terminal": True})
            self.assertEqual(launch.call_args.args[0], ["alacritty", "-e", "tool", "two words"])
            grid.launch_app({"name": "Fixture", "exec": 'tool https://example.test/%ff?q=%F0%20 %u', "terminal": False})
            self.assertEqual(launch.call_args.args[0], ["tool", "https://example.test/%ff?q=%F0%20"])

    def test_malformed_desktop_exec_does_not_launch(self):
        grid = load("polish_grid_invalid", "core/bin/omarchy-app-grid")
        with patch.dict(sys.modules, {"omarchy_session": SimpleNamespace(select_sway_socket=lambda _: "fixture")}), \
             patch.object(grid.subprocess, "check_output", return_value='{"nodes": []}'), \
             patch.object(grid.subprocess, "Popen") as launch:
            grid.launch_app({"name": "Fixture", "exec": 'tool "unterminated', "terminal": False})
            launch.assert_not_called()

    def test_installer_writes_dispatcher_entry_with_desktop_quoting(self):
        script = (ROOT / "core/bin/omarchy-installer.sh").read_text()
        functions = script.split("ensure_apt_updated\n\ncase", 1)[0].split("ensure_apt_updated()", 1)[1]
        functions = "ensure_apt_updated()" + functions
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            body = directory / "installer-test.sh"
            url = 'https://example.test/%ff?q=%F0%20&query="two words"&ratio=100%&back=\\path&cash=$5&tick=`x`'
            body.write_text('set -e\nAPP_DIR="$1"\nICON_DIR="$1"\nOMARCHY_PATH="$1"\n' + functions + '\nwrite_launcher() { cat > "$1"; }\ninstall_webapp_desktop "Fixture" "$2" fixture\n')
            result = subprocess.run(["/bin/bash", str(body), tmp, url], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            line = next(line for line in (directory / "Fixture.desktop").read_text().splitlines() if line.startswith("Exec="))
            self.assertTrue(line.startswith('Exec=/usr/local/bin/omarchy-launch-webapp "'))
            grid = load("polish_grid_installed", "core/bin/omarchy-app-grid")
            with patch.object(grid, "DESKTOP_DIRS", [tmp]), \
                 patch.dict(sys.modules, {"omarchy_session": SimpleNamespace(select_sway_socket=lambda _: "fixture")}), \
                 patch.object(grid.subprocess, "check_output", return_value='{"nodes": []}'), \
                 patch.object(grid.subprocess, "Popen") as launch:
                app = next(app for app in grid.load_desktop_apps() if app["id"] == "Fixture")
                grid.launch_app(app)
                self.assertEqual(launch.call_args.args[0], ["/usr/local/bin/omarchy-launch-webapp", url])

    def test_firefox_wrapper_reuses_its_dedicated_profile_without_no_remote(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            browser = root / "firefox"
            browser.write_text(f"#!{sys.executable}\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            browser.chmod(0o755)
            wrapper = root / "wrapper"
            wrapper.write_text((ROOT / "core/bin/omarchy-firefox").read_text().replace("/usr/bin/firefox", str(browser)))
            url = "https://www.bilibili.com/?one=1&two=2"
            result = subprocess.run(["/bin/bash", str(wrapper), url], env={**os.environ, "HOME": tmp}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), ["--profile", str(root / ".local/share/omarchy-firefox/profile"), url])

    def test_fix_desktops_self_heals_stale_icon_and_preserves_custom_exec(self):
        fix = load("polish_fix_desktops", "core/bin/omarchy-fix-desktops")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app_dir = root / "apps"
            app_dir.mkdir()
            exe = root / "code"
            exe.write_text("#!/bin/sh\n")
            exe.chmod(0o755)
            cmd = f"{exe} --flag"
            target = app_dir / "omarchy-android-code.desktop"
            # Simulate a stale/wrong Icon= from an earlier release
            target.write_text(
                f"[Desktop Entry]\nType=Application\nName=Visual Studio Code (Omarchy)\n"
                f"TryExec={exe}\nExec={cmd}\nIcon=chatgpt\nTerminal=false\nCategories=Utility;\n"
            )
            with patch.object(fix, "resolve_icon", return_value="vscode"):
                fix.publish(app_dir, "code", "Visual Studio Code", str(exe), cmd)
            self.assertIn("Icon=vscode\n", target.read_text())

            # User-customized Exec= must be preserved untouched
            custom = target.read_text().replace(f"Exec={cmd}", f"Exec={exe} --my-custom-flag")
            target.write_text(custom)
            with patch.object(fix, "resolve_icon", return_value="other-icon"):
                fix.publish(app_dir, "code", "Visual Studio Code", str(exe), cmd)
            self.assertEqual(target.read_text(), custom)

    def test_upstream_image_includes_svg_icon_loader(self):
        self.assertIn("librsvg2-common", (ROOT / "install.sh").read_text())
        self.assertIn("librsvg2-common", (ROOT / ".github/workflows/build-container.yml").read_text())


if __name__ == "__main__":
    unittest.main()
