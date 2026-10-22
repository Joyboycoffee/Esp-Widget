"""esptool runner and background flash worker.

The desktop app flashes one merged factory image at offset 0x0. A merged
image keeps the bootloader, partition table and application in sync, so a
single write can recover a blank ESP32-C3 as well as update a running one.
"""

from __future__ import annotations

import io
import sys
import threading
import time
from contextlib import redirect_stderr, redirect_stdout
from typing import Callable, Optional

from .core import (
    FALLBACK_BAUD,
    FLASH_BAUD,
    FirmwareInfo,
    LineCollector,
    friendly_error,
    parse_chip,
    parse_mac,
    parse_progress,
)

Emitter = Callable[[str, object], None]


class FlashError(RuntimeError):
    """A flash attempt failed with a message suitable for the UI."""


class _CallbackWriter(io.TextIOBase):
    """Make esptool feel like an unbuffered, non-TTY stream."""

    def __init__(self, sink: Callable[[str], None]):
        self._sink = sink

    def write(self, text: str) -> int:
        if text:
            self._sink(text)
        return len(text or "")

    def flush(self) -> None:
        return None

    def isatty(self) -> bool:
        return False


class EsptoolRunner:
    """Run one esptool command and stream its decoded output."""

    def __init__(self, on_line: Callable[[str], None]):
        self._on_line = on_line
        self.lines: list[str] = []

    def run(self, args: list[str]) -> int:
        try:
            import esptool
        except Exception as exc:  # pragma: no cover - packaging guard
            raise FlashError(
                "The bundled flash engine is unavailable. Rebuild the flasher."
            ) from exc

        collector = LineCollector(self._capture)
        writer = _CallbackWriter(collector.feed)

        try:
            with redirect_stdout(writer), redirect_stderr(writer):
                esptool.main(args)
        except SystemExit as exc:
            code = exc.code if isinstance(exc.code, int) else 0
            if code not in (0, None):
                raise FlashError(self._last_message()) from exc
            return code
        except Exception as exc:
            message = str(exc).strip() or self._last_message()
            raise FlashError(message) from exc
        finally:
            collector.flush()
            try:
                writer.flush()
            except Exception:
                pass
        return 0

    def _capture(self, line: str) -> None:
        cleaned = (line or "").strip()
        if cleaned:
            self.lines.append(cleaned)
        self._on_line(line)

    def _last_message(self) -> str:
        for line in reversed(self.lines):
            lowered = line.lower()
            if lowered and not lowered.startswith("esptool.py v"):
                return line
        return "esptool did not return an error message."


def _noop(kind: str, payload: object) -> None:
    return None


def probe_board(port: str, emit: Emitter = _noop) -> tuple[str, str, str]:
    """Find a reset mode that reaches the chip and return (mode, chip, MAC)."""

    last_error = "No response from the board."

    for index, mode in enumerate(("usb_reset", "default_reset")):
        label = "USB-C reset" if mode == "usb_reset" else "serial reset"
        emit("stage", f"Connecting with {label}…")
        try:
            runner = EsptoolRunner(_make_line_handler(emit))
            runner.run(
                [
                    "--chip",
                    "esp32c3",
                    "--port",
                    port,
                    "--baud",
                    str(FLASH_BAUD),
                    "--before",
                    mode,
                    "--after",
                    "no_reset",
                    "flash_id",
                ]
            )
        except FlashError as exc:
            last_error = str(exc)
            if index == 0:
                emit(
                    "log",
                    ("USB reset did not wake the board; trying a serial reset…", "warn"),
                )
                time.sleep(0.35)
            continue

        chip = next(
            (name for name in (parse_chip(line) for line in runner.lines) if name),
            "ESP32-C3",
        )
        mac = next(
            (addr for addr in (parse_mac(line) for line in runner.lines) if addr),
            "",
        )
        return mode, chip, mac

    raise FlashError(friendly_error(last_error))


def _make_line_handler(emit: Emitter):
    def handle(line: str) -> None:
        text = (line or "").strip()
        if not text:
            return

        percent = parse_progress(text)
        if percent is not None:
            emit("progress", percent)
            if percent in (0, 25, 50, 75, 100):
                emit("log", (f"Writing… {percent}%", "muted"))
            return

        lowered = text.lower()
        if "erasing flash" in lowered:
            emit("stage", "Erasing flash…")
        elif "hash of data verified" in lowered:
            emit("stage", "Verified — restarting…")
            emit("log", ("Image verified against flash.", "ok"))
        elif "hard resetting" in lowered or "leaving..." in lowered:
            emit("stage", "Restarting the widget…")
        elif "chip is esp32" in lowered:
            emit("stage", "Chip detected — preparing…")
        elif "compressed" in lowered and "bytes" in lowered:
            emit("stage", "Writing firmware…")

        # Repeated reset chatter belongs in the log but is not interesting.
        emit("log", (text, "muted" if text.startswith("esptool.py") else "info"))

    return handle


def _write_image(
    port: str,
    firmware: FirmwareInfo,
    before: str,
    baud: int,
    emit: Emitter,
) -> None:
    emit("stage", "Writing firmware…")
    emit("progress", 0)

    args = [
        "--chip",
        "esp32c3",
        "--port",
        port,
        "--baud",
        str(baud),
        "--before",
        before,
        "--after",
        "hard_reset",
        "write_flash",
        "-z",
        "--verify",
        "--flash_mode",
        firmware.flash_mode,
        "--flash_freq",
        firmware.flash_freq,
        "--flash_size",
        firmware.flash_size,
        firmware.offset,
        str(firmware.path),
    ]
    runner = EsptoolRunner(_make_line_handler(emit))
    runner.run(args)
    emit("progress", 100)


def flash_port(
    port: str,
    firmware: FirmwareInfo,
    emit: Emitter = _noop,
) -> tuple[str, str]:
    """Probe and flash a port, returning (chip, MAC)."""

    mode, chip, mac = probe_board(port, emit)
    emit("board", {"port": port, "chip": chip, "mac": mac})
    emit("log", (f"Connected to {chip}" + (f" ({mac})" if mac else ""), "ok"))

    try:
        _write_image(port, firmware, mode, FLASH_BAUD, emit)
    except FlashError as exc:
        if FLASH_BAUD == FALLBACK_BAUD:
            raise
        emit(
            "log",
            (f"High-speed write failed ({exc}). Retrying at 115200 baud…", "warn"),
        )
        time.sleep(0.6)
        _write_image(port, firmware, mode, FALLBACK_BAUD, emit)

    return chip, mac


class FlashWorker(threading.Thread):
    """Run one flash in the background and report through ``emit``."""

    def __init__(
        self,
        port: str,
        firmware: FirmwareInfo,
        emit: Emitter,
        auto: bool = False,
    ):
        super().__init__(name=f"flash-{port}", daemon=True)
        self.port = port
        self.firmware = firmware
        self.emit = emit
        self.auto = auto
        self.started_at = 0.0
        self.finished_at: Optional[float] = None

    def run(self) -> None:
        self.started_at = time.monotonic()
        try:
            self.emit(
                "log",
                (
                    f"Board connected on {self.port}. Starting automatic flash…"
                    if self.auto
                    else f"Starting flash on {self.port}…"
                ),
                "info",
            )
            chip, mac = flash_port(self.port, self.firmware, self.emit)
            self.finished_at = time.monotonic()
            elapsed = self.finished_at - self.started_at
            self.emit(
                "done",
                {
                    "port": self.port,
                    "chip": chip,
                    "mac": mac,
                    "elapsed": elapsed,
                    "version": self.firmware.version,
                },
            )
        except FlashError as exc:
            self.finished_at = time.monotonic()
            self.emit("error", friendly_error(str(exc)))
        except Exception as exc:  # pragma: no cover - unexpected worker failure
            self.finished_at = time.monotonic()
            self.emit("error", friendly_error(str(exc)))
