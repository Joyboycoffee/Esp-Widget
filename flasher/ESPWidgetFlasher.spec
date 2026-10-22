# -*- mode: python ; coding: utf-8 -*-
"""One-file portable build for the ESP Widget USB flasher."""

from pathlib import Path

from PyInstaller.utils.hooks import collect_all

here = Path(SPECPATH)

esptool_datas, esptool_binaries, esptool_hidden = collect_all("esptool")

datas = list(esptool_datas)
datas += [
    (str(here / "esp_flasher" / "assets"), "esp_flasher/assets"),
    (str(here / "resources"), "resources"),
]

a = Analysis(
    [str(here / "run_flasher.py")],
    pathex=[str(here)],
    binaries=list(esptool_binaries),
    datas=datas,
    hiddenimports=list(esptool_hidden) + ["serial.tools.list_ports"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["pytest", "IPython", "matplotlib", "numpy"],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="ESPWidgetFlasher",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(here / "resources" / "app.ico"),
    version=str(here / "resources" / "version_info.txt"),
    uac_admin=False,
)
