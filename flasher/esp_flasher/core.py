"""Shared, hardware-free helpers for the ESP Widget desktop flasher."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

try:  # pyserial is a hard runtime dependency, but keep imports testable.
    from serial.tools import list_ports
except Exception:  # pragma: no cover - only hit in a broken install
    list_ports = None


APP_NAME = "ESP Widget Flasher"
APP_VERSION = "1.0.0"

ESP_VID = 0x303A
BRIDGE_VIDS = {
    0x10C4: "CP210x",
    0x1A86: "CH340",
    0x0403: "FTDI",
    0x2341: "Arduino",
}

FLASH_OFFSET = "0x0"
FLASH_MODE = "dio"
FLASH_FREQ = "80m"
FLASH_SIZE = "4MB"
FLASH_BAUD = 460800
FALLBACK_BAUD = 115200

FIRMWARE_MANIFEST = "firmware-manifest.json"


class FirmwareError(RuntimeError):
    """The bundled release image is missing or corrupt."""


@dataclass(frozen=True)
class PortInfo:
    device: str
    description: str = ""
    vid: Optional[int] = None
    pid: Optional[int] = None
    hwid: str = ""

    @property
    def is_espressif(self) -> bool:
        return self.vid == ESP_VID

    @property
    def is_bridge(self) -> bool:
        return self.vid in BRIDGE_VIDS

    @property
    def bridge_name(self) -> str:
        return BRIDGE_VIDS.get(self.vid or -1, "USB serial")

    @property
    def kind(self) -> str:
        if self.is_espressif:
            return "ESP32 USB"
        if self.is_bridge:
            return self.bridge_name
        if _looks_like_esp(self.description):
            return "ESP32 serial"
        return "Serial"

    @property
    def label(self) -> str:
        description = (self.description or "Serial port").strip()
        if len(description) > 46:
            description = description[:43].rstrip() + "…"
        return f"{self.device}  ·  {description}"


@dataclass(frozen=True)
class FirmwareInfo:
    path: Path
    version: str
    built_utc: str
    sha256: str
    size: int
    chip: str
    flash_mode: str
    flash_freq: str
    flash_size: str
    offset: str

    @property
    def short_hash(self) -> str:
        return self.sha256[:12]

    @property
    def size_label(self) -> str:
        return human_size(self.size)


def _looks_like_esp(description: str) -> bool:
    text = (description or "").upper()
    return "ESP32" in text or "ESP8266" in text or "JTAG" in text


def _vid_pid_from_hwid(hwid: str) -> tuple[Optional[int], Optional[int]]:
    vid = re.search(r"VID_([0-9A-Fa-f]{4})", hwid or "")
    pid = re.search(r"PID_([0-9A-Fa-f]{4})", hwid or "")
    return (
        int(vid.group(1), 16) if vid else None,
        int(pid.group(1), 16) if pid else None,
    )


def _natural_port_key(device: str) -> tuple:
    match = re.search(r"(\d+)$", device or "")
    prefix = (device or "").rstrip("0123456789")
    return (prefix.lower(), int(match.group(1)) if match else 0, device)


def list_serial_ports(include_bluetooth: bool = False) -> list[PortInfo]:
    """Return usable serial ports, hiding Windows Bluetooth pseudo-ports by default."""

    if list_ports is None:
        return []

    ports: list[PortInfo] = []
    try:
        enumerated = list(list_ports.comports())
    except Exception:
        return []

    for port in enumerated:
        hwid = getattr(port, "hwid", "") or ""
        description = getattr(port, "description", "") or ""
        if not include_bluetooth and (
            "BTHENUM" in hwid.upper() or "BLUETOOTH" in description.upper()
        ):
            continue
        vid = getattr(port, "vid", None)
        pid = getattr(port, "pid", None)
        if vid is None or pid is None:
            hwid_vid, hwid_pid = _vid_pid_from_hwid(hwid)
            vid = vid if vid is not None else hwid_vid
            pid = pid if pid is not None else hwid_pid
        info = PortInfo(
            device=getattr(port, "device", ""),
            description=description,
            vid=vid,
            pid=pid,
            hwid=hwid,
        )
        if info.device:
            ports.append(info)

    return sorted(ports, key=lambda item: _natural_port_key(item.device))


def is_auto_candidate(port: PortInfo, include_bridges: bool = False) -> bool:
    """Only auto-flash ports that are unambiguously an ESP32 or an allowed bridge."""

    if port.is_espressif or _looks_like_esp(port.description):
        return True
    return bool(include_bridges and port.is_bridge)


def pick_auto_port(
    ports: Iterable[PortInfo], include_bridges: bool = False
) -> Optional[PortInfo]:
    candidates = [port for port in ports if is_auto_candidate(port, include_bridges)]
    if not candidates:
        return None
    # Prefer native ESP32 USB over a generic bridge.
    candidates.sort(key=lambda item: (not item.is_espressif, _natural_port_key(item.device)))
    return candidates[0]


def parse_progress(line: str) -> Optional[int]:
    """Extract the percentage from esptool's ``Writing at ... (42 %)`` output."""

    if "%" not in (line or ""):
        return None
    match = re.search(r"\(\s*(\d{1,3})\s*%\s*\)", line)
    if match is None:
        match = re.search(r"(\d{1,3})\s*%", line)
    if match is None:
        return None
    value = int(match.group(1))
    return max(0, min(100, value))


def parse_chip(line: str) -> Optional[str]:
    match = re.search(r"Chip is\s+(.+?)\s*(?:\(|$)", line or "")
    if match is None:
        return None
    return match.group(1).strip()


def parse_mac(line: str) -> Optional[str]:
    match = re.search(r"MAC:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})", line or "")
    return match.group(1).upper() if match else None


def friendly_error(message: str) -> str:
    """Turn esptool's long diagnostics into one actionable sentence."""

    text = (message or "").strip()
    lowered = text.lower()

    if any(
        phrase in lowered
        for phrase in (
            "failed to connect",
            "no serial data received",
            "timed out waiting for packet header",
            "invalid head of packet",
            "chip stopped responding",
        )
    ):
        return (
            "Could not talk to the board. Hold BOOT, tap RESET, release BOOT, "
            "then click Retry flash."
        )
    if any(
        phrase in lowered
        for phrase in (
            "doesn't exist",
            "does not exist",
            "file not found",
            "filenotfounderror",
            "no such file",
        )
    ):
        return (
            "No board was found on that port. Reconnect the USB-C cable and pick "
            "the COM port that appears."
        )
    if any(
        phrase in lowered
        for phrase in (
            "could not open port",
            "access is denied",
            "permission denied",
            "device is busy",
            "port is busy",
        )
    ):
        return (
            "The serial port is busy. Close PlatformIO Monitor, Arduino IDE, "
            "or any other serial terminal, then retry."
        )
    if "wrong chip" in lowered or "chip is esp32" in lowered:
        return "That port is not an ESP32-C3. Select the ESP Widget board and retry."
    if "no such file" in lowered or "cannot find" in lowered:
        return "The USB cable disconnected during flashing. Reconnect the board and retry."
    if "hash of data verified" in lowered:
        return "The image was written but verification was interrupted. Retry the flash."
    if not text:
        return "The flash did not finish. Reconnect the board and retry."
    # Strip esptool's repeated "A fatal error occurred:" prefix.
    text = re.sub(r"^a fatal error occurred:\s*", "", text, flags=re.I)
    return text[0].upper() + text[1:] if text else text


def human_size(size: int) -> str:
    value = float(max(0, size))
    for suffix in ("B", "KB", "MB", "GB"):
        if value < 1024 or suffix == "GB":
            if suffix == "B":
                return f"{int(value)} B"
            return f"{value:.1f} {suffix}"
        value /= 1024
    return f"{value:.1f} GB"


def bundle_root() -> Path:
    """Root used for bundled resources in both source and PyInstaller builds."""

    frozen = getattr(sys, "_MEIPASS", None)
    if frozen:
        return Path(frozen)
    return Path(__file__).resolve().parent.parent


def asset_path(name: str) -> Path:
    if getattr(sys, "_MEIPASS", None):
        return bundle_root() / "esp_flasher" / "assets" / name
    return Path(__file__).resolve().parent / "assets" / name


def resource_path(name: str) -> Path:
    return bundle_root() / "resources" / name


def app_directory() -> Path:
    """Folder containing the running app, or the source folder in development."""

    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_firmware() -> FirmwareInfo:
    """Load and validate the release image embedded next to the app."""

    manifest_path = asset_path(FIRMWARE_MANIFEST)
    if not manifest_path.exists():
        raise FirmwareError(
            "The bundled firmware manifest is missing. Rebuild the flasher with "
            "tools/build_release.ps1."
        )

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise FirmwareError(f"The bundled firmware manifest is invalid: {exc}") from exc

    filename = str(manifest.get("file") or "esp-widget-full.bin")
    image_path = asset_path(filename)
    if not image_path.exists():
        raise FirmwareError(f"The bundled firmware image is missing ({filename}).")

    actual_size = image_path.stat().st_size
    expected_size = int(manifest.get("size") or 0)
    if expected_size and actual_size != expected_size:
        raise FirmwareError(
            f"Firmware size mismatch (expected {expected_size} bytes, got {actual_size})."
        )

    with image_path.open("rb") as handle:
        magic = handle.read(1)
    if magic != b"\xE9":
        raise FirmwareError("The bundled image is not an ESP32 flash image.")

    expected_hash = str(manifest.get("sha256") or "").lower()
    actual_hash = _sha256(image_path)
    if expected_hash and actual_hash != expected_hash:
        raise FirmwareError(
            "The bundled firmware checksum does not match its manifest. "
            "Rebuild the flasher."
        )

    return FirmwareInfo(
        path=image_path,
        version=str(manifest.get("version") or APP_VERSION),
        built_utc=str(manifest.get("built_utc") or ""),
        sha256=actual_hash,
        size=actual_size,
        chip=str(manifest.get("chip") or "esp32c3"),
        flash_mode=str(manifest.get("flash_mode") or FLASH_MODE),
        flash_freq=str(manifest.get("flash_freq") or FLASH_FREQ),
        flash_size=str(manifest.get("flash_size") or FLASH_SIZE),
        offset=str(manifest.get("offset") or FLASH_OFFSET),
    )


def open_in_file_manager(path: Path) -> None:
    """Best-effort reveal helper used by the UI."""

    target = path if path.exists() else path.parent
    if not target.exists():
        return
    if sys.platform.startswith("win"):
        os.startfile(str(target))  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        import subprocess

        subprocess.Popen(["open", str(target)])
    else:
        import subprocess

        subprocess.Popen(["xdg-open", str(target)])


class LineCollector:
    """Split streamed esptool output into lines, accepting CR and LF endings."""

    def __init__(self, on_line, max_buffer: int = 16_384):
        self._on_line = on_line
        self._buffer = ""
        self._max_buffer = max_buffer

    def feed(self, text: str) -> None:
        if not text:
            return
        self._buffer += text.replace("\r\n", "\n").replace("\r", "\n")
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            self._on_line(line)
        if len(self._buffer) > self._max_buffer:
            self._on_line(self._buffer)
            self._buffer = ""

    def flush(self) -> None:
        if self._buffer:
            self._on_line(self._buffer)
            self._buffer = ""
