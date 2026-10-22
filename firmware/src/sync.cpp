#include "sync.h"

#include <LittleFS.h>
#include <NimBLEDevice.h>
#include <esp_system.h>

#include "app_state.h"
#include "ble.h"
#include "board_config.h"
#include "clock.h"
#include "espw_protocol.h"
#include "net.h"
#include "power.h"
#include "ui.h"

namespace espsync {
namespace {
uint16_t s_lastSeq[256];
bool s_seqInit = false;
File s_photo;
uint16_t s_photoTotal = 0;
uint16_t s_photoWritten = 0;
bool s_photoOpen = false;

bool validLayout(const espw::LayoutPayload& layout) {
  if (layout.pages < 1 || layout.pages > 5) return false;
  for (uint8_t p = 0; p < layout.pages; ++p) {
    if (layout.page[p].template_id > espw::TemplateGrid) return false;
    for (uint8_t s = 0; s < 4; ++s) {
      const auto& slot = layout.page[p].slots[s];
      if (slot.type != 0xFF && slot.type >= espw::WidgetCount) return false;
      if (slot.accent > espw::AccentRose) return false;
    }
  }
  return true;
}

void factoryReset() {
  Serial.println("[SYNC] factory reset requested");
  if (s_photoOpen) s_photo.close();
  LittleFS.remove("/photo.jpg");
  gPrefs.clear();
  NimBLEDevice::deleteAllBonds();
  delay(250);
  ESP.restart();
}
}  // namespace

void sendAck(uint16_t seq, uint8_t status) {
  espw::AckPayload ack{seq, status};
  ble::send(static_cast<uint8_t>(espw::MsgType::Ack), &ack, sizeof(ack));
}

void sendNack(uint16_t seq, uint8_t reason) {
  espw::AckPayload ack{seq, reason};
  ble::send(static_cast<uint8_t>(espw::MsgType::Nack), &ack, sizeof(ack));
}

void sendHello() {
  espw::Hello hello{};
  strlcpy(hello.name, ble::deviceName(), sizeof(hello.name));
  hello.fw_major = ESPW_VERSION_MAJOR;
  hello.fw_minor = ESPW_VERSION_MINOR;
  hello.fw_patch = ESPW_VERSION_PATCH;
  hello.protocol = espw::kProtocolVersion;
  hello.max_frame = espw::kMaxFrame;
  hello.screen_w = SCREEN_W;
  hello.screen_h = SCREEN_H;
  hello.pages = statePageCount();
  ble::send(static_cast<uint8_t>(espw::MsgType::Hello), &hello, sizeof(hello));
}

void sendStatus() {
  espw::StatusPayload status{};
  status.page = stateCurrentPage();
  status.ble_connected = gState.bleConnected ? 1 : 0;
  status.wifi_state = gState.wifiState;
  status.rssi = gState.wifiRssi;
  status.battery = gState.battery.percent;
  status.millivolts = gState.battery.millivolts;
  status.heap_kb = static_cast<uint16_t>(ESP.getFreeHeap() / 1024);
  status.uptime = millis() / 1000;
  status.epoch = espclock::epochUtc();
  status.layout_version = static_cast<uint16_t>(gState.layoutVersion);
  status.charging = gState.charging ? 1 : 0;
  status.usb = gState.usbPower ? 1 : 0;
  status.charge_full = gState.chargeFull ? 1 : 0;
  ble::send(static_cast<uint8_t>(espw::MsgType::Status), &status, sizeof(status));
}

void sendTime() {
  espw::TimePayload time{};
  time.epoch = espclock::epochUtc();
  ble::send(static_cast<uint8_t>(espw::MsgType::Time), &time, sizeof(time));
}

void sendBattery() {
  ble::send(static_cast<uint8_t>(espw::MsgType::Battery), &gState.battery,
            sizeof(gState.battery));
}

void handleFrame(uint8_t type, const uint8_t* payload, uint16_t len, uint16_t seq) {
  if (!s_seqInit) {
    memset(s_lastSeq, 0xFF, sizeof(s_lastSeq));
    s_seqInit = true;
  }
  if (type < 256 && s_lastSeq[type] == seq) {
    sendAck(seq, 0);  // duplicate, already applied
    return;
  }
  bool applied = true;
  switch (static_cast<espw::MsgType>(type)) {
    case espw::MsgType::HelloAck:
      break;
    case espw::MsgType::Time:
      if (len >= sizeof(espw::TimePayload)) {
        espw::TimePayload t{};
        memcpy(&t, payload, sizeof(t));
        espclock::sync(t.epoch, t.tz_offset_min, (t.flags & 0x01) != 0);
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Weather:
      if (len >= sizeof(espw::WeatherPayload)) {
        memcpy(&gState.weather, payload, sizeof(espw::WeatherPayload));
        gState.weatherValid = gState.weather.updated != 0;
        gPrefs.putBytes("weather", &gState.weather, sizeof(gState.weather));
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Battery:
      if (len >= sizeof(espw::BatteryPayload)) {
        memcpy(&gState.battery, payload, sizeof(espw::BatteryPayload));
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Stock:
      if (len >= sizeof(espw::StockPayload)) {
        espw::StockPayload s{};
        memcpy(&s, payload, sizeof(s));
        const uint8_t index = s.index % 6;
        gState.stocks[index] = s;
        gState.stockValid[index] = true;
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Notification:
      if (len >= sizeof(espw::NotificationPayload)) {
        memcpy(&gState.notification, payload, sizeof(gState.notification));
        gState.notificationValid = true;
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Media:
      if (len >= sizeof(espw::MediaPayload)) {
        memcpy(&gState.media, payload, sizeof(gState.media));
        gState.mediaValid = true;
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Calendar:
      if (len >= sizeof(espw::CalendarPayload)) {
        memcpy(&gState.calendar, payload, sizeof(gState.calendar));
        gState.calendarValid = true;
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Steps:
      if (len >= sizeof(espw::StepsPayload)) {
        memcpy(&gState.steps, payload, sizeof(gState.steps));
        gState.stepsValid = true;
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::Text:
      if (len >= sizeof(espw::TextPayload)) {
        espw::TextPayload t{};
        memcpy(&t, payload, sizeof(t));
        if (t.slot < 4) {
          strlcpy(gState.texts[t.slot], t.text, sizeof(gState.texts[t.slot]));
          stateSaveTexts();
          gState.dirty = true;
        } else applied = false;
      } else applied = false;
      break;
    case espw::MsgType::Location:
      if (len >= sizeof(espw::LocationPayload)) {
        espw::LocationPayload loc{};
        memcpy(&loc, payload, sizeof(loc));
        if (loc.lat_e6 >= -90000000 && loc.lat_e6 <= 90000000 &&
            loc.lon_e6 >= -180000000 && loc.lon_e6 <= 180000000) {
          gState.location.lat_e6 = loc.lat_e6;
          gState.location.lon_e6 = loc.lon_e6;
          strlcpy(gState.location.city, loc.city, sizeof(gState.location.city));
          stateSaveLocation();
          gState.dirty = true;
        } else applied = false;
      } else applied = false;
      break;
    case espw::MsgType::Layout:
      if (len >= sizeof(espw::LayoutPayload)) {
        espw::LayoutPayload layout{};
        memcpy(&layout, payload, sizeof(layout));
        if (validLayout(layout)) {
          gState.layout = layout;
          if (gState.settings.current_page >= layout.pages) gState.settings.current_page = 0;
          stateSaveLayout();
          stateSaveSettings();
          gState.dirty = true;
        } else applied = false;
      } else applied = false;
      break;
    case espw::MsgType::Settings:
      if (len >= sizeof(espw::SettingsPayload)) {
        espw::SettingsPayload s{};
        memcpy(&s, payload, sizeof(s));
        if (s.brightness >= 10 && s.rotation <= 3 && s.sleep_sec <= 240) {
          if (s.current_page >= statePageCount()) s.current_page = 0;
          gState.settings = s;
          stateSaveSettings();
          power::setBrightness(s.brightness);
          uiApplyRotation();
          gState.dirty = true;
        } else applied = false;
      } else applied = false;
      break;
    case espw::MsgType::WifiScan:
      net::requestScan();
      stateSetToast("Scanning WiFi...", 1500);
      break;
    case espw::MsgType::WifiSet:
      if (len >= sizeof(espw::WifiSetPayload)) {
        espw::WifiSetPayload w{};
        memcpy(&w, payload, sizeof(w));
        char ssid[33] = {0};
        char pass[65] = {0};
        memcpy(ssid, w.ssid, sizeof(w.ssid));
        memcpy(pass, w.password, sizeof(w.password));
        net::setCredentials(ssid, pass, w.connect != 0);
        stateSetToast("WiFi saved");
        gState.dirty = true;
      } else applied = false;
      break;
    case espw::MsgType::WifiForget:
      net::forget();
      stateSetToast("WiFi forgotten");
      break;
    case espw::MsgType::PhotoBegin:
      if (len >= sizeof(espw::PhotoBegin)) {
        espw::PhotoBegin p{};
        memcpy(&p, payload, sizeof(p));
        if (s_photoOpen) s_photo.close();
        s_photo = LittleFS.open("/photo.jpg", "w");
        s_photoOpen = s_photo;
        s_photoTotal = p.total;
        s_photoWritten = 0;
      } else applied = false;
      break;
    case espw::MsgType::PhotoData:
      if (s_photoOpen && len >= 4) {
        uint16_t offset = 0;
        memcpy(&offset, payload, 2);
        const uint8_t* data = payload + 4;
        const uint16_t dataLen = len - 4;
        if (offset != s_photoWritten) s_photo.seek(offset);
        const size_t wrote = s_photo.write(data, dataLen);
        s_photoWritten = offset + wrote;
      } else applied = false;
      break;
    case espw::MsgType::PhotoEnd:
      if (s_photoOpen) {
        s_photo.close();
        s_photoOpen = false;
        gState.photoReady = LittleFS.exists("/photo.jpg");
        stateSetToast("Photo updated");
        gState.dirty = true;
      }
      break;
    case espw::MsgType::Ping:
      ble::send(static_cast<uint8_t>(espw::MsgType::Pong), payload, len);
      break;
    case espw::MsgType::StatusReq:
      sendStatus();
      break;
    case espw::MsgType::TimeReq:
      sendTime();
      break;
    case espw::MsgType::FactoryReset:
      if (len >= sizeof(espw::FactoryResetPayload)) {
        espw::FactoryResetPayload r{};
        memcpy(&r, payload, sizeof(r));
        if (r.confirm == 0xA5) factoryReset();
      }
      break;
    default:
      applied = false;
      break;
  }
  if (type < 256) s_lastSeq[type] = seq;
  if (type != static_cast<uint8_t>(espw::MsgType::FactoryReset)) {
    sendAck(seq, applied ? 0 : 1);
  }
}

}  // namespace espsync
