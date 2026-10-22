# ESP Widget USB Flasher

Portable Windows flasher for the ESP32-C3 widget. The release exe embeds the
merged firmware image; no Python or PlatformIO is needed on the target PC.

## Run from source

```powershell
cd "C:\Users\Gourav\Documents\Random Projects\Esp Widget"
.\.flasher-venv\Scripts\python.exe -m pip install -r flasher\requirements.txt
cd flasher
..\.flasher-venv\Scripts\python.exe -m esp_flasher --demo
```

`--demo` shows a simulated COM7 board. Without it the app watches real ports.

## Tests

```powershell
cd "C:\Users\Gourav\Documents\Random Projects\Esp Widget\flasher"
..\.flasher-venv\Scripts\python.exe -m unittest discover -s tests -v
..\.flasher-venv\Scripts\python.exe -m esp_flasher --self-test
..\.flasher-venv\Scripts\python.exe -m esp_flasher --smoke-ui
```

## Package

The release script (`tools\build_release.ps1`) copies the merged image and
manifest into `esp_flasher\assets`, regenerates the icon, then runs PyInstaller
with `ESPWidgetFlasher.spec`. To do just the desktop part:

```powershell
cd "C:\Users\Gourav\Documents\Random Projects\Esp Widget"
.\.flasher-venv\Scripts\python.exe -m PyInstaller --clean --noconfirm `
  --distpath release\desktop --workpath flasher\build flasher\ESPWidgetFlasher.spec
```

The output is a single `ESPWidgetFlasher.exe` (about 12 MB) in
`release\desktop`.

## Layout

```
esp_flasher/
├── core.py      firmware manifest, port detection, progress parsing, errors
├── flash.py     esptool probe/write worker
├── ui.py        Tkinter window
└── __main__.py  GUI plus --list, --flash, --self-test, --demo, --smoke-ui
resources/       icon and Windows version metadata
tests/           hardware-free unit tests
```

`core.py` and `flash.py` do not import Tk, which keeps them testable on a
headless machine. `FlashWorker` only talks to the UI through the emitter
callback, so a future Qt or CLI front end can reuse it unchanged.
