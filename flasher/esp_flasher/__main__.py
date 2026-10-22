"""Entry point for the ESP Widget desktop flasher."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

from .core import (
    APP_NAME,
    APP_VERSION,
    FirmwareError,
    is_auto_candidate,
    list_serial_ports,
    load_firmware,
    resource_path,
)
from .flash import FlashError, flash_port

# esptool redirects sys.stdout while it runs. Keep a handle to the real
# console so CLI logs do not feed back into esptool's output collector.
_CONSOLE = sys.__stdout__


def _emit_console(kind: str, payload: object) -> None:
    if _CONSOLE is None:
        return
    if kind == "log":
        text, _level = payload  # type: ignore[misc]
        print(text, file=_CONSOLE)
    elif kind == "progress":
        print(
            f"\rWriting… {int(payload):3d}%",
            end="",
            flush=True,
            file=_CONSOLE,
        )  # type: ignore[arg-type]
    elif kind == "stage":
        print(f"\n{payload}", file=_CONSOLE)
    elif kind == "done":
        print("\nFlash complete.", file=_CONSOLE)
    elif kind == "error":
        print(f"\nError: {payload}", file=_CONSOLE)


def _write_json(path: Optional[str], data: dict) -> None:
    rendered = json.dumps(data, indent=2)
    if path:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered + "\n", encoding="utf-8")
    try:
        print(rendered)
    except Exception:
        pass


def _self_test(output: Optional[str]) -> int:
    checks: dict[str, object] = {
        "app": APP_NAME,
        "version": APP_VERSION,
        "ok": False,
    }
    errors: list[str] = []

    try:
        firmware = load_firmware()
        checks["firmware"] = {
            "version": firmware.version,
            "file": firmware.path.name,
            "size": firmware.size,
            "sha256": firmware.sha256,
            "chip": firmware.chip,
            "offset": firmware.offset,
        }
        with firmware.path.open("rb") as handle:
            magic = handle.read(1)
        if magic != b"\xE9":
            errors.append("firmware image has no ESP32 magic byte")
    except FirmwareError as exc:
        errors.append(str(exc))

    try:
        import esptool

        checks["esptool"] = getattr(esptool, "__version__", "unknown")
    except Exception as exc:
        errors.append(f"esptool import failed: {exc}")

    try:
        import serial

        checks["pyserial"] = serial.__version__
    except Exception as exc:
        errors.append(f"pyserial import failed: {exc}")

    try:
        import tkinter

        checks["tkinter"] = str(tkinter.TkVersion)
    except Exception as exc:
        errors.append(f"tkinter import failed: {exc}")

    icon = resource_path("app.ico")
    checks["icon"] = str(icon)
    if not icon.exists():
        errors.append(f"icon missing: {icon}")

    checks["errors"] = errors
    checks["ok"] = not errors
    _write_json(output, checks)
    return 0 if not errors else 1


def _list_ports(output: Optional[str]) -> int:
    ports = list_serial_ports()
    data = {
        "ok": True,
        "ports": [
            {
                "device": port.device,
                "description": port.description,
                "vid": f"0x{port.vid:04X}" if port.vid is not None else None,
                "pid": f"0x{port.pid:04X}" if port.pid is not None else None,
                "kind": port.kind,
                "auto": is_auto_candidate(port),
            }
            for port in ports
        ],
    }
    _write_json(output, data)
    return 0


def _flash(port: str) -> int:
    try:
        firmware = load_firmware()
        print(f"{APP_NAME} v{APP_VERSION}")
        print(f"Firmware v{firmware.version} · {firmware.size_label} · {firmware.short_hash}")
        print(f"Port: {port}")
        flash_port(port, firmware, _emit_console)
        return 0
    except (FirmwareError, FlashError) as exc:
        print(f"\nFlash failed: {exc}")
        return 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ESPWidgetFlasher",
        description="Flash the ESP Widget firmware over USB-C.",
    )
    parser.add_argument("--version", action="version", version=f"{APP_NAME} v{APP_VERSION}")
    parser.add_argument("--demo", action="store_true", help="show the UI with a fake board")
    parser.add_argument(
        "--smoke-ui",
        action="store_true",
        help="open the UI, render briefly, then close",
    )
    parser.add_argument("--self-test", action="store_true", help="validate the bundled runtime")
    parser.add_argument("--list", action="store_true", help="list serial ports")
    parser.add_argument("--flash", metavar="PORT", help="flash a port without opening the UI")
    parser.add_argument("--output", metavar="FILE", help="write JSON results to FILE")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = _parser().parse_args(argv)

    if args.self_test:
        return _self_test(args.output)
    if args.list:
        return _list_ports(args.output)
    if args.flash:
        return _flash(args.flash)

    from .ui import run_app

    demo = bool(args.demo or args.smoke_ui)
    auto_close = 1800 if args.smoke_ui else 0
    return run_app(demo=demo, auto_close_ms=auto_close)


if __name__ == "__main__":
    sys.exit(main())
