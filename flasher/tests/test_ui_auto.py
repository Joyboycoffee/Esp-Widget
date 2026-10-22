from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from esp_flasher.core import PortInfo  # noqa: E402


class FakeWorker:
    started: list[tuple[str, bool]] = []

    def __init__(self, port, firmware, emit, auto=False):
        self.port = port
        self.auto = auto
        self.daemon = True

    def start(self):
        FakeWorker.started.append((self.port, self.auto))


def esp_port() -> PortInfo:
    return PortInfo(
        device="COM7",
        description="USB JTAG/serial debug unit",
        vid=0x303A,
        pid=0x1001,
        hwid="USB VID_303A&PID_1001",
    )


class AutoFlashTests(unittest.TestCase):
    def setUp(self):
        FakeWorker.started = []
        try:
            import tkinter  # noqa: F401
            from esp_flasher.ui import FlasherApp
        except Exception as exc:  # pragma: no cover - headless CI
            self.skipTest(f"Tkinter is unavailable: {exc}")
        self.FlasherApp = FlasherApp

    def _app(self):
        try:
            return self.FlasherApp()
        except Exception as exc:  # pragma: no cover - headless CI
            self.skipTest(f"Tk display is unavailable: {exc}")

    def test_native_board_starts_automatic_flash(self):
        app = self._app()
        try:
            with mock.patch("esp_flasher.ui.list_serial_ports", return_value=[esp_port()]), \
                 mock.patch("esp_flasher.ui.FlashWorker", FakeWorker):
                app._on_insert(esp_port())
                app._auto_flash_if_ready("COM7")
            self.assertEqual(FakeWorker.started, [("COM7", True)])
        finally:
            app.destroy()

    def test_generic_serial_is_not_auto_flashed(self):
        generic = PortInfo("COM1", "Communications Port")
        app = self._app()
        try:
            with mock.patch("esp_flasher.ui.list_serial_ports", return_value=[generic]), \
                 mock.patch("esp_flasher.ui.FlashWorker", FakeWorker):
                app._on_insert(generic)
                app._auto_flash_if_ready("COM1")
            self.assertEqual(FakeWorker.started, [])
        finally:
            app.destroy()

    def test_disabling_auto_prevents_the_start(self):
        app = self._app()
        try:
            app.auto_var.set(False)
            with mock.patch("esp_flasher.ui.list_serial_ports", return_value=[esp_port()]), \
                 mock.patch("esp_flasher.ui.FlashWorker", FakeWorker):
                app._on_insert(esp_port())
                app._auto_flash_if_ready("COM7")
            self.assertEqual(FakeWorker.started, [])
        finally:
            app.destroy()


if __name__ == "__main__":
    unittest.main()
