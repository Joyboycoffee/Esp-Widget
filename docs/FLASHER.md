# Desktop USB Flasher

`ESPWidgetFlasher.exe` is a small portable Windows app that writes the complete
ESP Widget firmware to an ESP32-C3 over the same USB-C cable you use for power.

It bundles esptool, the bootloader, the partition table and the application, so
the target computer does not need Python, PlatformIO, Arduino IDE or a driver
install for the native USB-C port.

![ESP Widget Flasher](../screenshots/flasher.png)

## Quick start

1. Open `release\desktop\ESPWidgetFlasher.exe`.
2. Leave **Auto-flash on connect** enabled.
3. Plug the widget into USB-C.
4. Wait for the status to change to **Firmware flashed** and the progress bar to
   reach 100%.

The board restarts itself when the write is verified. The app shows the port,
chip, MAC address, firmware version, image size and SHA-256 before and after the
flash.

## What "automatic" means

- The app polls Windows serial ports once a second and filters out Bluetooth
  pseudo-ports.
- A native Espressif USB device (VID `303A`) or a port whose description says
  ESP32/JTAG is treated as a board. Clicking **Include USB-serial adapters**
  also accepts CH340, CP210x and FTDI bridges.
- A newly connected board is flashed after a 1.6 s settle delay. The write is
  not repeated while the same board stays plugged in, and there is a 25 s
  cooldown so a reset/re-enumeration during flashing cannot loop.
- **Flash now** always works manually, including for a port that was not
  recognized automatically.
- Removing the board clears the board card and returns the app to
  **Waiting for your widget**.

Auto-flash is intentionally opt-in for generic USB-serial bridges: a CH340 or
CP210x can belong to an unrelated microcontroller.

## Flashing details

The release build merges the PlatformIO artifacts into one factory image:

| Offset | Contents |
| --- | --- |
| `0x0` | ESP32-C3 bootloader |
| `0x8000` | partition table |
| `0x10000` | application |

The app writes that image at `0x0` with `dio` / `80 MHz` / `4 MB` settings,
compresses the transfer, and verifies the written data. A single write therefore
works on a blank board as well as on an already-running widget. NVS settings and
the photo partition are outside the written range and survive an update.

The flasher tries the ESP32-C3 native USB reset first, then the classic serial
DTR/RTS reset. If both fail it leaves the board alone and shows the BOOT-button
instructions instead of retrying forever.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| No COM port appears | Try a different USB-C cable; some are power-only. Use a data cable. |
| Port is busy | Close PlatformIO Monitor, Arduino IDE, PuTTY or any serial terminal. |
| Cannot talk to the board | Hold **BOOT**, tap **RESET**, release **BOOT**, then click **Retry flash**. |
| Board is in a reboot loop | Reconnect while holding **BOOT**, then flash again. |
| Windows SmartScreen warning | The exe is not code-signed. Choose **More info → Run anyway**, or build it yourself. |
| A CH340/CP210x board is ignored | Enable **Include USB-serial adapters**, or select the port and click **Flash now**. |

The app only accepts an ESP32-C3. If another Espressif chip is connected, the
probe fails with a clear message and nothing is written.

## Build the release

From the repository root on Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_release.ps1
```

The script:

1. Runs a clean PlatformIO release build of `firmware/`.
2. Merges the bootloader, partitions and app into
   `release\firmware\esp-widget-<version>-full.bin`.
3. Writes `release\firmware\firmware-manifest.json` with size and SHA-256.
4. Embeds that image into the flasher and runs PyInstaller.
5. Runs the packaged `--self-test` and a GUI smoke test.
6. Builds a signed `release\android\EspWidget-<version>-release.apk`.
7. Writes `release\SHA256SUMS.txt` and `release\BUILD-INFO.txt`.

Useful switches:

```powershell
.\tools\build_release.ps1 -SkipFirmware
.\tools\build_release.ps1 -SkipAndroid
.\tools\build_release.ps1 -SkipFlasher
.\tools\build_release.ps1 -SkipSmokeTest
```

### Manual commands

Firmware:

```powershell
cd firmware
& "$env:USERPROFILE\.platformio\penv\Scripts\pio.exe" run
```

Merge the factory image (the build environment is created by the release
script; the command below assumes it exists):

```powershell
# From the repository root
.\.flasher-venv\Scripts\python.exe -m esptool --chip esp32c3 merge_bin `
  -o release\firmware\esp-widget-1.0.0-full.bin `
  --flash_mode dio --flash_freq 80m --flash_size 4MB `
  0x0 firmware\.pio\build\esp32-c3-devkitm-1\bootloader.bin `
  0x8000 firmware\.pio\build\esp32-c3-devkitm-1\partitions.bin `
  0x10000 firmware\.pio\build\esp32-c3-devkitm-1\firmware.bin
```

Signed Android release:

```powershell
cd app
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease
```

The Gradle file reads `app\keystore.properties` when it exists. The release
script creates a local JKS and that properties file on first use. Keep both
files: Android only accepts updates signed with the same key. If a debug build
is already installed on the phone, uninstall it once before installing the
release APK because the two use different signing keys.

Portable flasher:

```powershell
# From the repository root
.\.flasher-venv\Scripts\python.exe -m PyInstaller --clean --noconfirm `
  --distpath release\desktop --workpath flasher\build `
  flasher\ESPWidgetFlasher.spec
```

### Flasher development

```powershell
cd flasher
..\.flasher-venv\Scripts\python.exe -m unittest discover -s tests -v
..\.flasher-venv\Scripts\python.exe -m esp_flasher --demo
..\.flasher-venv\Scripts\python.exe -m esp_flasher --list
```

The package also supports headless use:

```powershell
# Write a JSON report without a console (the exe is windowed)
.\release\desktop\ESPWidgetFlasher.exe --self-test --output self-test.json

# Flash a known port without opening the window
.\release\desktop\ESPWidgetFlasher.exe --flash COM7
```

`--demo` shows a simulated COM7 board and never touches hardware. `--smoke-ui`
opens and closes the UI in under two seconds, which is what the release script
uses to catch a broken layout or missing resource.

## Release layout

```
release/
├── android/
│   └── EspWidget-1.0.0-release.apk
├── desktop/
│   ├── ESPWidgetFlasher.exe
│   └── self-test.json
├── firmware/
│   ├── esp-widget-1.0.0-full.bin
│   ├── bootloader.bin
│   ├── firmware.bin
│   ├── partitions.bin
│   └── firmware-manifest.json
├── BUILD-INFO.txt
└── SHA256SUMS.txt
```

`self-test.json` is a build receipt: it proves the packaged exe found its
embedded firmware, esptool, pyserial, Tk and icon.
