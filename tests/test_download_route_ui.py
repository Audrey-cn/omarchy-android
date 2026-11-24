"""Download route persistence and update-center controls without a GTK session."""

import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]


class Widget:
    def __init__(self, **kwargs):
        self.label = kwargs.get("label", "")
        self.children = []
        self.classes = set()
        self.signals = {}

    def get_style_context(self):
        return self

    def add_class(self, value):
        self.classes.add(value)

    def remove_class(self, value):
        self.classes.discard(value)

    def pack_start(self, child, *_):
        self.children.append(child)

    pack_end = pack_start

    def add(self, child):
        self.children.append(child)

    def connect(self, signal, callback):
        self.signals[signal] = callback

    def get_children(self):
        return self.children

    def __getattr__(self, name):
        if name.startswith("set_") or name in ("show", "show_all", "hide", "destroy", "grab_focus"):
            return lambda *args: None
        raise AttributeError(name)


class DownloadRouteUiTests(unittest.TestCase):
    def setUp(self):
        loader = importlib.machinery.SourceFileLoader("download_route_panel", str(ROOT / "core/bin/omarchy-panel"))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        self.panel = importlib.util.module_from_spec(spec)
        gtk = SimpleNamespace(Window=Widget, Box=Widget, Label=Widget, Button=Widget,
                              Expander=Widget, Orientation=SimpleNamespace(HORIZONTAL=0, VERTICAL=1))
        with patch.dict(sys.modules, {
            "gi": SimpleNamespace(require_version=lambda *_: None),
            "gi.repository": SimpleNamespace(Gtk=gtk, Gdk=None, GLib=SimpleNamespace(idle_add=lambda *_: None),
                                              GtkLayerShell=None),
        }):
            loader.exec_module(self.panel)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.config = Path(folder.name) / "nested/download-mirror.json"
        self.panel.DOWNLOAD_MIRROR_CFG = str(self.config)

    def test_direct_default_and_immediate_persistence(self):
        self.assertEqual(self.panel.load_download_mirror(), {"mode": "direct", "custom_prefix": ""})
        for mode in ("accel", "direct"):
            self.panel.save_download_mirror(mode)
            self.assertEqual(json.loads(self.config.read_text()), {"mode": mode, "custom_prefix": ""})
        self.panel.save_download_mirror("custom", " https://mirror.example/proxy ")
        self.assertEqual(self.panel.load_download_mirror(), {
            "mode": "custom", "custom_prefix": "https://mirror.example/proxy/"})

    def test_invalid_custom_prefix_cannot_replace_selected_route(self):
        self.panel.save_download_mirror("accel")
        for prefix in ("http://mirror.example/", "https://", "https://user@mirror.example/",
                       "https://mirror.example/?url=", "https://mirror.example/#fragment",
                       "https://mirror.example:bad/", "https://mirror.example/path\nother"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                self.panel.save_download_mirror("custom", prefix)
            self.assertEqual(self.panel.load_download_mirror()["mode"], "accel")
        with self.assertRaises(ValueError):
            self.panel.save_download_mirror("unknown")

    def test_malformed_preferences_use_direct(self):
        self.config.parent.mkdir()
        for value in ("[]", "{", '{"mode":"unknown"}', '{"mode":"custom","custom_prefix":"http://bad"}'):
            self.config.write_text(value)
            self.assertEqual(self.panel.load_download_mirror(), {"mode": "direct", "custom_prefix": ""})

    def test_route_card_is_visible_before_advanced_and_click_saves(self):
        panel = self.make_panel()
        card_index = next(i for i, card in enumerate(panel.box.children)
                          if any(child.label == "下载线路" for child in card.children))
        advanced_index = next(i for i, card in enumerate(panel.box.children) if card.label == "高级维护与故障修复")
        self.assertLess(card_index, advanced_index)
        card = panel.box.children[card_index]
        controls = next(row for row in card.children if len(row.children) == 3)
        self.assertEqual([button.label for button in controls.children], ["GitHub 直连", "国内加速", "自定义"])
        controls.children[1].signals["clicked"](controls.children[1])
        self.assertEqual(json.loads(self.config.read_text())["mode"], "accel")
        self.assertIn("om-btn-active", controls.children[1].classes)

    def test_custom_dialog_stays_open_for_invalid_prefix_and_saves_valid_choice(self):
        panel = self.make_panel()
        responses = iter((1, 1))
        values = iter(("http://insecure.example/", "https://custom.example/prefix"))
        dialog = Widget()
        dialog.add_buttons = lambda *_: None
        dialog.get_content_area = Widget
        dialog.run = lambda: next(responses)
        entry = Widget()
        entry.get_text = lambda: next(values)
        gtk = self.panel.Gtk
        with patch.object(gtk, "Dialog", return_value=dialog, create=True), \
                patch.object(gtk, "Entry", return_value=entry, create=True), \
                patch.object(gtk, "ResponseType", SimpleNamespace(OK=1, CANCEL=0), create=True):
            panel._select_download_route("custom")
        self.assertEqual(json.loads(self.config.read_text()), {
            "mode": "custom", "custom_prefix": "https://custom.example/prefix/"})

    def test_custom_dialog_cancel_preserves_current_selection(self):
        panel = self.make_panel()
        panel._select_download_route("accel")
        dialog = Widget()
        dialog.add_buttons = lambda *_: None
        dialog.get_content_area = Widget
        dialog.run = lambda: 0
        gtk = self.panel.Gtk
        with patch.object(gtk, "Dialog", return_value=dialog, create=True), \
                patch.object(gtk, "Entry", Widget, create=True), \
                patch.object(gtk, "ResponseType", SimpleNamespace(OK=1, CANCEL=0), create=True):
            panel._select_download_route("custom")
        self.assertEqual(json.loads(self.config.read_text())["mode"], "accel")

    def make_panel(self):
        panel = self.panel.OmarchyPanel.__new__(self.panel.OmarchyPanel)
        Widget.__init__(panel)
        panel.box = Widget()
        panel._load_update_rows = lambda: {"desktop": {"state": "current"}, "apt": {"state": "current"}}
        panel._render_update_state = lambda _: None
        panel.build_update()
        return panel


if __name__ == "__main__":
    unittest.main()
