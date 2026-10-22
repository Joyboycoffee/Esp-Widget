# ESP Widget Protocol v1

One protocol is implemented twice: `firmware/lib/espw_protocol/espw_protocol.h` (C++17, no Arduino dependencies) and `app/app/src/main/java/dev/gourav/espwidget/protocol/` (Kotlin). Both are tested against `protocol/vectors.json`.

All multi-byte integers are **little-endian**. Structs are packed (`#pragma pack(1)`).

## Frame

```
offset  size  field
0       1     magic 'E' (0x45)
1       1     magic 'W' (0x57)
2       1     version = 1
3       1     type
4       1     flags (0)
5       2     seq
7       2     payload length (0..1024)
9       n     payload
9+n     2     CRC-16/CCITT-FALSE, low byte then high byte
```

The CRC is computed over bytes **2 through the end of the payload** (everything after the magic). It is the same CRC used by `crc16()` in the firmware and `Wire.crc16()` in Kotlin: initial value `0xFFFF`, polynomial `0x1021`, no reflection, no final XOR. The reference check value for `"123456789"` is `0x29B1` (10673).

A frame is valid only when the magic and version match, the declared length is ≤ 1024, the received length is exactly `11 + length`, and the CRC matches. Invalid frames are dropped; an invalid inbound frame triggers a `NACK` (0x33) with status 1.

## BLE transport

| Item | UUID |
| --- | --- |
| Service | `8f1d0001-6a4d-4c1e-9a1e-73e2f4b1c001` |
| RX (phone → widget) | `8f1d0002-6a4d-4c1e-9a1e-73e2f4b1c001` |
| TX (widget → phone) | `8f1d0003-6a4d-4c1e-9a1e-73e2f4b1c001` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

The device advertises as `ESP-WIDGET-XXXX` and requires an encrypted, bonded link with a six-digit passkey (display-only IO capability). The app writes to RX with write-with-response and subscribes to TX notifications. MTU 247 is requested; the default 23 is supported.

### Fragmentation

```
offset  size  field
0       1     0xF1
1       1     frame id (rolling)
2       1     part index (0-based)
3       1     part count (1..32)
4       1     reserved
5       n     frame bytes
```

- Chunk payload is `MTU - 3 - 5` bytes, capped at 239.
- A frame may use at most 32 chunks.
- Part 0 carries the frame header and establishes the chunk stride. Subsequent parts may arrive out of order; each is placed at `part × stride`. Duplicate parts are ignored.
- A new frame id (or a different part count) resets the assembler.
- The sender must therefore keep every frame under `32 × chunkPayload`. The Android photo sender queries the current MTU and shrinks its payload accordingly.

## Messages

Type values are stable. `dir` is `P→W` (phone to widget) or `W→P`.

| Type | Name | Dir | Payload |
| --- | --- | --- | --- |
| 0x01 | Hello | W→P | `char name[16]; u8 fw_major; u8 fw_minor; u8 fw_patch; u8 protocol; u16 max_frame; u16 screen_w; u16 screen_h; u8 pages; u8 reserved[3]` = 30 |
| 0x02 | HelloAck | P→W | `u8 protocol; u8 app_major; u8 app_minor; u8 reserved; char app_name[16]; u32 capabilities` = 24 |
| 0x10 | Time | P→W | `u32 epoch; i16 tz_offset_min; u8 flags; u8 reserved` = 8; flags bit0 DST |
| 0x11 | Weather | P→W | `i16 temp_c10; i16 hi_c10; i16 lo_c10; u8 code; u8 humidity; u8 precip; u8 is_day; i16 trend_c10[12]; u16 aqi; u32 updated; char city[16]; u8 trend_count; u8 reserved` = 58 |
| 0x12 | Battery | P→W | `u8 percent; u16 millivolts; u8 charging; u8 usb; u32 updated` = 9 |
| 0x13 | Stock | P→W | `char symbol[12]; i32 price_cents; i16 change_bp; i16 spark[24]; u8 currency; u8 market; u32 updated; u8 index; u8 reserved[3]` = 76 |
| 0x14 | Notification | P→W | `char app[16]; char title[40]; char body[56]; u8 count; u8 flags; u32 updated` = 118 |
| 0x15 | Media | P→W | `char title[40]; char artist[24]; u8 playing; u8 progress_pct; u32 updated` = 70 |
| 0x16 | Calendar | P→W | `char title[40]; u32 start; u8 all_day; char location[16]; u32 updated` = 65 |
| 0x17 | Steps | P→W | `u32 steps; u32 goal; u32 updated` = 12 |
| 0x18 | Text | P→W | `char text[96]; u8 slot; u8 reserved[3]` = 100 |
| 0x19 | Location | P→W | `i32 lat_e6; i32 lon_e6; char city[16]` = 24 |
| 0x20 | Layout | P→W | `u8 pages; PageConfig page[5]; u8 version; u8 reserved[2]` = 89 |
| 0x21 | Settings | P→W | `u8 brightness; u8 sleep_sec; u8 rotation; u8 flags; u8 current_page; u8 reserved[3]` = 8 |
| 0x22 | WifiScan | P→W | empty |
| 0x23 | WifiScanResult | W→P | `u8 count; WifiAp aps[8]` = 273; WifiAp = `char ssid[32]; i8 rssi; u8 auth` |
| 0x24 | WifiSet | P→W | `char ssid[32]; char password[64]; u8 connect` = 97 |
| 0x25 | WifiForget | P→W | empty |
| 0x26 | PhotoBegin | P→W | `u16 total; u16 width; u16 height; u8 format; u8 reserved` = 10; format 1 = JPEG |
| 0x27 | PhotoData | P→W | `u16 offset; u16 length; u8 data[length]` |
| 0x28 | PhotoEnd | P→W | empty |
| 0x30 | Ping | both | echoed bytes |
| 0x31 | Pong | both | echoed bytes |
| 0x32 | Ack | both | `u16 seq; u8 status` = 3 |
| 0x33 | Nack | both | `u16 seq; u8 reason` = 3 |
| 0x34 | StatusReq | P→W | empty |
| 0x35 | Status | W→P | `u8 page; u8 ble_connected; u8 wifi_state; i8 rssi; u8 battery; u16 mV; u16 heap_kb; u32 uptime; u32 epoch; u16 layout_version; u8 charging; u8 usb; u8 charge_full` = 22 |
| 0x36 | FactoryReset | P→W | `u8 confirm; u8 reserved[3]`; confirm must be `0xA5` |
| 0x37 | TimeReq | W→P | empty |

### Layout

```
struct SlotConfig { u8 type; u8 option; u8 accent; u8 reserved; }        // 4 bytes
struct PageConfig { u8 template_id; SlotConfig slots[4]; }               // 17 bytes
struct LayoutPayload {
  u8 pages;              // 1..5
  PageConfig page[5];    // unused pages are zero-filled
  u8 version;
  u8 reserved[2];
};                                                                      // 89 bytes
```

- `template_id`: `0` = hero + two small cards, `1` = 2 × 2 grid.
- `type`: `0` clock, `1` weather, `2` battery, `3` stocks, `4` notifications, `5` media, `6` calendar, `7` steps, `8` text, `9` photo, `0xFF` empty.
- `option`: watchlist index 0..5 for stocks; note index 0..3 for text; otherwise 0.
- `accent`: `0` peach, `1` sage, `2` sky, `3` lavender, `4` butter, `5` rose.
- The widget rejects a layout with more than five pages, an unknown template/type/accent, and acknowledges it as failed.

### Settings flags

`bit0` 12-hour clock, `bit1` Celsius, `bit2` WiFi enabled while docked, `bit3` show seconds. Brightness is 10..255, sleep is 0..240 seconds (0 disables sleep), rotation is 0..3.

## Handshake

1. The phone discovers the service, requests MTU 247 and enables TX notifications.
2. The widget sends `Hello` when notifications are subscribed.
3. The phone sends `HelloAck`; both sides can then exchange messages.
4. The phone sends a full snapshot (`Time`, `Layout`, `Settings`, `Weather`, `Stock` ×6, `Notification`, `Media`, `Calendar`, `Steps`, `Text` ×4, `Location`) and a `StatusReq`.

Every applied message is acknowledged with `Ack{seq, 0}`. Unsupported/invalid messages get `Ack{seq, 1}`. The widget ignores a repeated `(type, seq)` pair and re-acknowledges it, so retries are safe for idempotent messages. `FactoryReset` is not acknowledged.

## Payload encodings

- Strings are fixed-size UTF-8 fields, NUL padded and not necessarily NUL terminated when full. The firmware sanitises non-ASCII and newlines before drawing.
- `updated` is Unix seconds. A weather payload with `updated = 0` is treated as invalid.
- Temperature, high, low and the `trend_c10` hourly forecast are tenths of a degree Celsius; `287` = 28.7 °C. `trend_count` is 0..12; the unused tail of `trend_c10` is zero.
- Prices are integer cents/minor units. `change_bp` is basis points (`125` = +1.25 %). Sparkline points are basis points relative to the first point, clamped to ±2000 (±20 %). The app sends 24 samples (recent daily closes) and pads the rest with the last value.
- `charging`, `usb` and `charge_full` in `Status` come from the TP4056 LED sense pins plus the ADC rail, so the app can distinguish charging, docked-idle and on-battery without polling `Battery`.
- Currency: `0` INR, `1` USD, `2` other. Market: `0` closed, `1` open, `2` pre/post.
- Coordinates are degrees × 1,000,000.
- Notification, media and calendar timestamps are Unix seconds; the firmware only uses them for freshness.

## Photo transfer

1. `PhotoBegin{total, 240, 240, format=1}`.
2. One or more `PhotoData{offset, length, bytes}` messages. The widget writes at `offset` and tracks the highest written position.
3. `PhotoEnd` closes the file, marks `/photo.jpg` ready and refreshes the screen.

The app centre-crops, scales to 240 × 240, JPEG-compresses to ≤ 40 KB and reduces the per-message chunk size to fit the 32-fragment limit at the negotiated MTU. Offsets are 16-bit, so payloads are capped below 64 KB.

## Golden vectors

`protocol/vectors.json` contains:

- the CRC reference value for `"123456789"`;
- four complete frames (Ping, Time, Settings, Hello) with hex and expected fields;
- a six-byte-chunk fragmentation example with four chunks.

`tools/make_vectors.py` regenerates the C++ header and JSON from one source of truth. The firmware test suite consumes `test/vectors_generated.h`; the Kotlin suite reads the JSON from test resources.
