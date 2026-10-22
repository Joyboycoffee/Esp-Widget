# Hardware reference

## Board

- **MCU:** ESP32-C3 (single RISC-V core, 4 MB flash, native USB Serial/JTAG).
- **Display:** 240 × 240 ST7789 over 4-wire SPI, no CS (tied low), inversion enabled, colour order RGB.
- **Input:** one capacitive touch pad or momentary button on the touch pin.
- **Power:** single-cell LiPo through a TP4056, with the red/blue charge LEDs tapped for dock detection.
- **Battery sense:** 100 kΩ + 100 kΩ divider into the ADC pin.

The firmware is configured for `esp32-c3-devkitm-1` and uses the Arduino framework, LovyanGFX for the panel, NimBLE for Bluetooth LE, ArduinoJson for weather, LittleFS for the photo, and Preferences (NVS) for persistence.

## Pin profiles

The source repository this project was derived from contains three different pin tables. The firmware exposes all three and defaults to the one the working firmware actually compiles with.

| Function | Profile 0 (default) | Profile 1 | Profile 2 |
| --- | --- | --- | --- |
| TFT SCLK | 6 | 6 | 8 |
| TFT MOSI | 10 | 10 | 10 |
| TFT RES | 5 | 5 | 5 |
| TFT DC | 3 | 3 | 3 |
| TFT backlight | 4 | 2 | 2 |
| Touch / button | 1 | 4 | 1 |
| Debug LED | 8 | 8 | disabled |
| Battery ADC | 0 | 0 | 0 |
| TP4056 blue | 20 | 20 | 20 |
| TP4056 red | 21 | 21 | 21 |

Select a profile with a build flag:

```ini
build_flags = -DESPW_PIN_PROFILE=1
```

Profile 0 matches the `config.h`/`display_setup.h` pair in the original Keychain firmware. Profiles 1 and 2 match older README/wiring-guide tables. If your panel stays dark or the touch pin behaves oddly, confirm the physical wiring before changing profiles.

The TFT chip-select is not connected (`PIN_TFT_CS = -1`). The panel is write-only; the driver does not read pixels back.

## Display configuration

- SPI2 at 40 MHz write, mode 3, DMA auto.
- Inversion on, RGB order, 240 × 240, rotation set from the app's settings (default 270°).
- Backlight on GPIO 4 using LEDC PWM at 5 kHz.
- The UI draws into a full-screen `LGFX_Sprite` and pushes it as one sprite, which keeps page transitions flicker-free. The firmware builds at roughly 85 % flash and 16 % RAM.

## Touch and gestures

The touch pin is active high (`TOUCH_ACTIVE_LEVEL`). A short press advances exactly one page, wrapping from the last configured page (including a grid or custom page) back to the first. Holding for 1.5 s opens a five-minute Bluetooth pairing/advertising window and shows a toast. The first press also restores the backlight when it was dimmed, and each page change is persisted to NVS so the widget wakes on the same page.

## Power behaviour

- **Docked:** either USB-C port is detected from the ADC rail (≥ 2300 mV, or < 80 mV when the cell is absent) or from the TP4056 status pins. The widget stays awake, keeps BLE advertising, and may join WiFi.
- **Top bar:** the UI draws a full-width battery bar at y = 0..3, coloured green when charging/full/above 50 %, butter above 20 % and red below that, alongside the page name, page number, BLE/WiFi state and numeric battery percentage.
- **On battery:** WiFi is disabled, BLE advertising is limited to a 90 s window, brightness drops to 40 after 20 s idle, and the device deep-sleeps after `sleep_sec` (default 60 s).
- **Wake:** deep sleep uses GPIO wake on the touch pin, level high. The screen is redrawn from NVS on wake.
- **Low battery:** at ≤ 5 % the backlight is capped at 60 to stretch runtime.

Battery percentage uses a multi-point LiPo discharge curve, and the ADC divider multiplier is stored in NVS as `bat_cal` (default 2.16). If your percentage reads high or low, calibrate against a multimeter and update the multiplier.

## TP4056 sensing

The red and blue LED cathodes are wired to GPIO 21 and 20 through the stock resistors, with the anodes on the charger's 5 V rail. The firmware keeps both pins high-impedance and samples them with brief 20 µs pulldowns to avoid ghost glow and back-feeding the charger:

- charging: red low, blue high;
- full: red high, blue low;
- unplugged: both low.

The percent comes from a multi-point 1S LiPo curve on the calibrated ADC reading, and `charge_full` forces 100 %. The docking state is also inferred from the ESP32's own USB rail, so either USB-C port can power the board. The resulting `charging`, `usb` and `charge_full` flags are pushed in every BLE `Status` message and shown as Charging / Full / Docked / On battery in the app.

## Safety notes

- The ESP32-C3 is 3.3 V only. Do not feed 5 V into the ADC divider or the LED sense pins.
- Use a protected LiPo and verify polarity before connecting the TP4056.
- The stock TP4056 resistors may need the documented modification to limit charge current for a small cell; check your cell's datasheet.
- The firmware never enables WiFi while on battery, which keeps the radio and brown-out risk out of the untethered mode.
