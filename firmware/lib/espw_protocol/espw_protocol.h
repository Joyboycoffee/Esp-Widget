#pragma once
// ESP Widget shared wire protocol. Deliberately free of Arduino dependencies
// so the format can be built and tested on the host.
#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace espw {

constexpr uint8_t kMagic0 = 'E';
constexpr uint8_t kMagic1 = 'W';
constexpr uint8_t kProtocolVersion = 2;
constexpr size_t kHeaderSize = 9;   // magic..length
constexpr size_t kCrcSize = 2;
constexpr size_t kMaxPayload = 1024;
constexpr size_t kMaxFrame = kHeaderSize + kMaxPayload + kCrcSize;
constexpr uint8_t kFragMarker = 0xF1;
constexpr size_t kFragHeader = 5;    // marker, id, part, count, reserved
constexpr uint8_t kMaxFragParts = 32;
constexpr uint8_t kMaxPages = 10;  // pages the widget can hold

enum class MsgType : uint8_t {
  Hello = 0x01,
  HelloAck = 0x02,
  Time = 0x10,
  Weather = 0x11,
  Battery = 0x12,
  Stock = 0x13,
  Notification = 0x14,
  Media = 0x15,
  Calendar = 0x16,
  Steps = 0x17,
  Text = 0x18,
  Location = 0x19,
  PhoneBattery = 0x1A,  // payload: PhoneBatteryPayload (phone -> widget)
  NextAlarm = 0x1B,     // payload: AlarmPayload
  Countdown = 0x1C,     // payload: CountdownPayload
  Layout = 0x20,
  Settings = 0x21,
  WifiScan = 0x22,
  WifiScanResult = 0x23,
  WifiSet = 0x24,
  WifiForget = 0x25,
  PhotoBegin = 0x26,
  PhotoData = 0x27,
  PhotoEnd = 0x28,
  Ping = 0x30,
  Pong = 0x31,
  Ack = 0x32,
  Nack = 0x33,
  StatusReq = 0x34,
  Status = 0x35,
  FactoryReset = 0x36,
  TimeReq = 0x37,
  Command = 0x38,  // payload: CommandPayload
};

enum WidgetType : uint8_t {
  WidgetClock = 0,
  WidgetWeather = 1,
  WidgetBattery = 2,
  WidgetStocks = 3,
  WidgetNotifications = 4,
  WidgetMedia = 5,
  WidgetCalendar = 6,
  WidgetSteps = 7,
  WidgetText = 8,
  WidgetPhoto = 9,
  WidgetPhoneBattery = 10,
  WidgetAnalogClock = 11,
  WidgetWorldClock = 12,  // option: city index (see kZones in ui.cpp)
  WidgetProgress = 13,    // option: 0 day, 1 week, 2 month, 3 year
  WidgetSun = 14,         // sunrise / sunset from the saved location
  WidgetMoon = 15,
  WidgetMonth = 16,       // month calendar
  WidgetAlarm = 17,       // phone's next alarm
  WidgetCountdown = 18,   // days to a date set in the app
  WidgetDevice = 19,      // the widget's own status
  WidgetThought = 20,     // thought of the day
  WidgetCount = 21,
};

enum PageTemplate : uint8_t {
  TemplateHeroPair = 0,
  TemplateGrid = 1,
};

enum Accent : uint8_t {
  AccentPeach = 0,
  AccentSage,
  AccentSky,
  AccentLavender,
  AccentButter,
  AccentRose,
};

#pragma pack(push, 1)

struct FrameHeader {
  uint8_t magic0;
  uint8_t magic1;
  uint8_t version;
  uint8_t type;
  uint8_t flags;
  uint16_t seq;
  uint16_t length;
};

struct Hello {
  char name[16];
  uint8_t fw_major;
  uint8_t fw_minor;
  uint8_t fw_patch;
  uint8_t protocol;
  uint16_t max_frame;
  uint16_t screen_w;
  uint16_t screen_h;
  uint8_t pages;
  uint8_t reserved[3];
};

struct HelloAck {
  uint8_t protocol;
  uint8_t app_major;
  uint8_t app_minor;
  uint8_t reserved;
  char app_name[16];
  uint32_t capabilities;
};

struct TimePayload {
  uint32_t epoch;
  int16_t tz_offset_min;
  uint8_t flags;  // bit0: DST
  uint8_t reserved;
};

struct WeatherPayload {
  int16_t temp_c10;
  int16_t hi_c10;
  int16_t lo_c10;
  uint8_t code;
  uint8_t humidity;
  uint8_t precip;
  uint8_t is_day;
  int16_t trend_c10[12];  // next 12 hours, oldest first
  uint16_t aqi;
  uint32_t updated;
  char city[16];
  uint8_t trend_count;
  uint8_t reserved;
};

struct BatteryPayload {
  uint8_t percent;
  uint16_t millivolts;
  uint8_t charging;
  uint8_t usb;
  uint32_t updated;
};

struct PhoneBatteryPayload {
  uint8_t percent;
  uint8_t charging;
  uint32_t updated;
};

struct AlarmPayload {
  uint32_t epoch;  // UTC; 0 = no alarm
  char label[24];
};

struct CountdownPayload {
  uint32_t target;  // UTC of the target day; 0 = none
  char label[28];
};

struct StockPayload {
  char symbol[12];
  int32_t price_cents;
  int16_t change_bp;
  int16_t spark[24];
  uint8_t currency;   // 0 INR, 1 USD, 2 other
  uint8_t market;     // 0 closed, 1 open, 2 pre/post
  uint32_t updated;
  uint8_t index;      // watchlist slot 0..5
  uint8_t reserved[3];
};

struct NotificationPayload {
  char app[16];
  char title[40];
  char body[56];
  uint8_t count;
  uint8_t flags;
  uint32_t updated;
};

struct MediaPayload {
  char title[40];
  char artist[24];
  uint8_t playing;
  uint8_t progress_pct;
  uint32_t updated;
};

struct CalendarPayload {
  char title[40];
  uint32_t start;
  uint8_t all_day;
  char location[16];
  uint32_t updated;
};

struct StepsPayload {
  uint32_t steps;
  uint32_t goal;
  uint32_t updated;
};

struct TextPayload {
  char text[96];
  uint8_t slot;
  uint8_t reserved[3];
};

struct LocationPayload {
  int32_t lat_e6;
  int32_t lon_e6;
  char city[16];
};

struct SlotConfig {
  uint8_t type;
  uint8_t option;
  uint8_t accent;
  uint8_t reserved;
};

struct PageConfig {
  uint8_t template_id;
  SlotConfig slots[4];
};

struct LayoutPayload {
  uint8_t pages;
  PageConfig page[kMaxPages];
  uint8_t version;
  uint8_t reserved[2];
};

struct SettingsPayload {
  uint8_t brightness;
  uint8_t sleep_sec;
  uint8_t rotation;
  uint8_t flags;  // bit0 12h, bit1 celsius, bit2 wifi, bit3 seconds
  uint8_t current_page;
  uint8_t reserved[3];
};

struct WifiAp {
  char ssid[32];
  int8_t rssi;
  uint8_t auth;
};

struct WifiScanResult {
  uint8_t count;
  WifiAp aps[8];
};

struct WifiSetPayload {
  char ssid[32];
  char password[64];
  uint8_t connect;
};

enum Command : uint8_t {
  CmdWakeScreen = 1,  // turn the screen on and keep it awake for a while
  CmdDeepSleep = 2,   // radios off, screen off; only the touch button wakes it
  CmdScreenOff = 4,   // turn the screen off (touch or Wake brings it back)
  CmdSetPage = 3,     // show page reserved[0] (swipe on the phone preview)
};

struct CommandPayload {
  uint8_t command;
  uint8_t reserved[3];
};

struct PhotoBegin {
  uint16_t total;
  uint16_t width;
  uint16_t height;
  uint8_t format;  // 1 = jpeg
  uint8_t reserved;
};

struct PhotoData {
  uint16_t offset;
  uint16_t length;
  // bytes follow
};

struct AckPayload {
  uint16_t seq;
  uint8_t status;
};

struct StatusPayload {
  uint8_t page;
  uint8_t ble_connected;
  uint8_t wifi_state;  // 0 off, 1 connecting, 2 connected, 3 error
  int8_t rssi;
  uint8_t battery;
  uint16_t millivolts;
  uint16_t heap_kb;
  uint32_t uptime;
  uint32_t epoch;
  uint16_t layout_version;
  uint8_t charging;
  uint8_t usb;
  uint8_t charge_full;
};

struct FactoryResetPayload {
  uint8_t confirm;
  uint8_t reserved[3];
};

#pragma pack(pop)

static_assert(sizeof(Hello) == 30, "Hello layout");
static_assert(sizeof(HelloAck) == 24, "HelloAck layout");
static_assert(sizeof(TimePayload) == 8, "Time layout");
static_assert(sizeof(WeatherPayload) == 58, "Weather layout");
static_assert(sizeof(StockPayload) == 76, "Stock layout");
static_assert(sizeof(LayoutPayload) == 174, "Layout layout");
static_assert(sizeof(SettingsPayload) == 8, "Settings layout");
static_assert(sizeof(LocationPayload) == 24, "Location layout");
static_assert(sizeof(StatusPayload) == 22, "Status layout");

inline uint16_t crc16(const uint8_t* data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; ++i) {
    crc ^= static_cast<uint16_t>(data[i]) << 8;
    for (int b = 0; b < 8; ++b) {
      crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021)
                           : static_cast<uint16_t>(crc << 1);
    }
  }
  return crc;
}

inline bool decodeFrame(const uint8_t* data, size_t len, uint8_t& type,
                        uint16_t& seq, const uint8_t*& payload,
                        uint16_t& payload_len) {
  if (len < kHeaderSize + kCrcSize) return false;
  FrameHeader h;
  memcpy(&h, data, sizeof(h));
  if (h.magic0 != kMagic0 || h.magic1 != kMagic1) return false;
  if (h.version != kProtocolVersion) return false;
  if (h.length > kMaxPayload) return false;
  if (len != kHeaderSize + h.length + kCrcSize) return false;
  const uint16_t expected = static_cast<uint16_t>(data[len - 2]) |
                            (static_cast<uint16_t>(data[len - 1]) << 8);
  if (crc16(data + 2, len - 4) != expected) return false;
  type = h.type;
  seq = h.seq;
  payload = data + kHeaderSize;
  payload_len = h.length;
  return true;
}

inline size_t encodeFrame(uint8_t type, uint16_t seq, const void* payload,
                          uint16_t payload_len, uint8_t* out, size_t cap) {
  if (payload_len > kMaxPayload) return 0;
  const size_t total = kHeaderSize + payload_len + kCrcSize;
  if (cap < total) return 0;
  FrameHeader h{kMagic0, kMagic1, kProtocolVersion, type, 0, seq, payload_len};
  memcpy(out, &h, sizeof(h));
  if (payload_len && payload) memcpy(out + kHeaderSize, payload, payload_len);
  const uint16_t crc = crc16(out + 2, kHeaderSize - 2 + payload_len);
  out[kHeaderSize + payload_len] = static_cast<uint8_t>(crc & 0xFF);
  out[kHeaderSize + payload_len + 1] = static_cast<uint8_t>(crc >> 8);
  return total;
}

// Reassembles BLE transport chunks:
// [0xF1][frameId][partIndex][partCount][reserved][data...]
// Chunks may arrive out of order; duplicates are ignored.
class Reassembler {
 public:
  void reset() {
    active_ = false;
    id_ = 0;
    count_ = 0;
    received_ = 0;
    mask_ = 0;
    len_ = 0;
    chunk_size_ = 0;
    complete_ = false;
  }

  bool feed(const uint8_t* chunk, size_t chunk_len) {
    if (chunk_len < kFragHeader) return false;
    if (chunk[0] != kFragMarker) return false;
    const uint8_t id = chunk[1];
    const uint8_t part = chunk[2];
    const uint8_t count = chunk[3];
    const size_t data_len = chunk_len - kFragHeader;
    if (count == 0 || count > kMaxFragParts || part >= count) return false;
    if (!active_ || id != id_ || count != count_) {
      reset();
      active_ = true;
      id_ = id;
      count_ = count;
    }
    if (part == 0) {
      // Chunk zero carries the frame header and establishes the stride used
      // to place later chunks, so out-of-order parts can be written directly.
      if (data_len == 0) {
        reset();
        return false;
      }
      chunk_size_ = data_len;
    } else if (chunk_size_ == 0) {
      reset();
      return false;
    }
    if (mask_ & (1UL << part)) return false;
    const size_t offset = static_cast<size_t>(part) * chunk_size_;
    if (offset + data_len > sizeof(buf_)) {
      reset();
      return false;
    }
    memcpy(buf_ + offset, chunk + kFragHeader, data_len);
    mask_ |= (1UL << part);
    received_++;
    if (received_ == count_) {
      const size_t total = offset + data_len;
      if (total == 0 || total > sizeof(buf_)) {
        reset();
        return false;
      }
      len_ = total;
      complete_ = true;
      return true;
    }
    return false;
  }

  bool complete() const { return complete_; }
  const uint8_t* data() const { return buf_; }
  size_t length() const { return len_; }

 private:
  uint8_t buf_[kMaxFrame];
  bool active_ = false;
  bool complete_ = false;
  uint8_t id_ = 0;
  uint8_t count_ = 0;
  uint8_t received_ = 0;
  uint32_t mask_ = 0;
  size_t len_ = 0;
  size_t chunk_size_ = 0;
};

inline uint8_t fragmentCount(size_t frame_len, size_t chunk_payload) {
  if (chunk_payload == 0) return 0;
  const size_t n = (frame_len + chunk_payload - 1) / chunk_payload;
  return n > kMaxFragParts ? 0 : static_cast<uint8_t>(n);
}

inline size_t writeFragmentHeader(uint8_t* out, uint8_t id, uint8_t part,
                                  uint8_t count) {
  out[0] = kFragMarker;
  out[1] = id;
  out[2] = part;
  out[3] = count;
  out[4] = 0;
  return kFragHeader;
}

}  // namespace espw
