from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from esp_flasher import core  # noqa: E402
from esp_flasher.core import (  # noqa: E402
    FirmwareError,
    LineCollector,
    PortInfo,
    friendly_error,
    human_size,
    is_auto_candidate,
    parse_chip,
    parse_mac,
    parse_progress,
    pick_auto_port,
)


def port(device: str, vid=None, description="", pid=0x1001) -> PortInfo:
    return PortInfo(device=device, description=description, vid=vid, pid=pid)


class PortTests(unittest.TestCase):
    def test_espressif_native_usb_is_auto(self):
        item = port("COM7", 0x303A, "USB JTAG/serial debug unit")
        self.assertTrue(item.is_espressif)
        self.assertTrue(is_auto_candidate(item))
        self.assertEqual(item.kind, "ESP32 USB")

    def test_bridge_is_opt_in(self):
        item = port("COM4", 0x1A86, "USB-SERIAL CH340")
        self.assertTrue(item.is_bridge)
        self.assertFalse(is_auto_candidate(item))
        self.assertTrue(is_auto_candidate(item, include_bridges=True))
        self.assertEqual(item.kind, "CH340")

    def test_generic_serial_is_not_auto(self):
        item = port("COM1", None, "Communications Port")
        self.assertFalse(is_auto_candidate(item))
        self.assertEqual(item.kind, "Serial")

    def test_pick_auto_prefers_native_over_bridge(self):
        bridge = port("COM4", 0x10C4, "CP2102")
        native = port("COM7", 0x303A, "USB JTAG/serial")
        self.assertEqual(pick_auto_port([bridge, native], True), native)
        self.assertIsNone(pick_auto_port([bridge], False))

    def test_label_is_bounded(self):
        item = port("COM7", 0x303A, "X" * 200)
        self.assertLessEqual(len(item.label), 56)
        self.assertTrue(item.label.startswith("COM7"))


class ProgressTests(unittest.TestCase):
    def test_progress_formats(self):
        self.assertEqual(parse_progress("Writing at 0x00010000... (42 %)"), 42)
        self.assertEqual(parse_progress("Writing at 0x0... (7%)"), 7)
        self.assertEqual(parse_progress("Writing at 0x0... (100 %)"), 100)
        self.assertIsNone(parse_progress("Hash of data verified."))
        self.assertEqual(parse_progress("(250 %)"), 100)

    def test_chip_and_mac(self):
        self.assertEqual(
            parse_chip("Chip is ESP32-C3 (QFN32) (revision v0.4)"), "ESP32-C3"
        )
        self.assertEqual(
            parse_mac("MAC: a0:b1:c2:d3:e4:f5"), "A0:B1:C2:D3:E4:F5"
        )
        self.assertIsNone(parse_mac("no address"))

    def test_sizes(self):
        self.assertEqual(human_size(512), "512 B")
        self.assertEqual(human_size(2216304), "2.1 MB")


class ErrorTests(unittest.TestCase):
    def test_boot_hint(self):
        message = friendly_error("Failed to connect to ESP32-C3: No serial data received.")
        self.assertIn("BOOT", message)

    def test_busy_hint(self):
        message = friendly_error("could not open port 'COM7': Access is denied.")
        self.assertIn("busy", message.lower())

    def test_wrong_chip_hint(self):
        message = friendly_error("Wrong chip")
        self.assertIn("ESP32-C3", message)

    def test_prefix_is_removed(self):
        message = friendly_error("A fatal error occurred: Failed to connect")
        self.assertFalse(message.startswith("A fatal"))


class LineCollectorTests(unittest.TestCase):
    def test_crlf_and_cr(self):
        lines: list[str] = []
        collector = LineCollector(lines.append)
        collector.feed("one\rtwo\r\nthree\nfour")
        collector.flush()
        self.assertEqual(lines, ["one", "two", "three", "four"])

    def test_empty(self):
        lines: list[str] = []
        collector = LineCollector(lines.append)
        collector.feed("")
        collector.flush()
        self.assertEqual(lines, [])


class FirmwareTests(unittest.TestCase):
    def _make_bundle(self, root: Path, *, corrupt_hash=False, bad_magic=False):
        payload = (b"\x00" if bad_magic else b"\xE9") + b"firmware" * 16
        image = root / "image.bin"
        image.write_bytes(payload)
        digest = hashlib.sha256(payload).hexdigest()
        if corrupt_hash:
            digest = "0" * 64
        manifest = {
            "version": "9.9.9",
            "file": "image.bin",
            "size": len(payload),
            "sha256": digest,
            "chip": "esp32c3",
            "flash_mode": "dio",
            "flash_freq": "80m",
            "flash_size": "4MB",
            "offset": "0x0",
        }
        (root / core.FIRMWARE_MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")

    def test_valid_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._make_bundle(root)
            with mock.patch.object(core, "asset_path", side_effect=lambda name: root / name):
                firmware = core.load_firmware()
            self.assertEqual(firmware.version, "9.9.9")
            self.assertEqual(firmware.offset, "0x0")
            self.assertEqual(len(firmware.sha256), 64)

    def test_bad_hash_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._make_bundle(root, corrupt_hash=True)
            with mock.patch.object(core, "asset_path", side_effect=lambda name: root / name):
                with self.assertRaises(FirmwareError):
                    core.load_firmware()

    def test_bad_magic_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._make_bundle(root, bad_magic=True)
            with mock.patch.object(core, "asset_path", side_effect=lambda name: root / name):
                with self.assertRaises(FirmwareError):
                    core.load_firmware()

    def test_missing_manifest_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(core, "asset_path", side_effect=lambda name: root / name):
                with self.assertRaises(FirmwareError):
                    core.load_firmware()


class EsptoolTests(unittest.TestCase):
    def test_version_command_runs_without_hardware(self):
        from esp_flasher.flash import EsptoolRunner

        lines: list[str] = []
        runner = EsptoolRunner(lines.append)
        runner.run(["--chip", "esp32c3", "version"])
        self.assertTrue(any("esptool.py" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
