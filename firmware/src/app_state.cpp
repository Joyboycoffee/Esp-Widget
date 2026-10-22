#include "app_state.h"

#include <LittleFS.h>

#include "espw_protocol.h"

RuntimeState gState;
Preferences gPrefs;

namespace {

bool validWidget(uint8_t t) { return t < espw::WidgetCount; }
bool validAccent(uint8_t a) { return a <= espw::AccentRose; }

void setSlot(espw::PageConfig& page, uint8_t index, uint8_t type, uint8_t option, uint8_t accent) {
  page.slots[index] = {type, option, accent, 0};
}

void clearSlot(espw::PageConfig& page, uint8_t index) {
  page.slots[index] = {0xFF, 0, 0, 0};
}

}  // namespace

void stateBegin() {
  gPrefs.begin("espw", false);
  stateDefaultLayout();
  stateLoad();
}

void stateDefaultLayout() {
  memset(&gState.layout, 0, sizeof(gState.layout));
  gState.layout.pages = 4;
  gState.layout.version = 1;
  gState.layout.page[0].template_id = espw::TemplateHeroPair;
  setSlot(gState.layout.page[0], 0, espw::WidgetClock, 0, espw::AccentPeach);
  setSlot(gState.layout.page[0], 1, espw::WidgetWeather, 0, espw::AccentSky);
  setSlot(gState.layout.page[0], 2, espw::WidgetBattery, 0, espw::AccentSage);
  clearSlot(gState.layout.page[0], 3);

  gState.layout.page[1].template_id = espw::TemplateHeroPair;
  setSlot(gState.layout.page[1], 0, espw::WidgetStocks, 0, espw::AccentButter);
  setSlot(gState.layout.page[1], 1, espw::WidgetNotifications, 0, espw::AccentRose);
  setSlot(gState.layout.page[1], 2, espw::WidgetMedia, 0, espw::AccentLavender);
  clearSlot(gState.layout.page[1], 3);

  gState.layout.page[2].template_id = espw::TemplateHeroPair;
  setSlot(gState.layout.page[2], 0, espw::WidgetCalendar, 0, espw::AccentLavender);
  setSlot(gState.layout.page[2], 1, espw::WidgetBattery, 0, espw::AccentSage);
  setSlot(gState.layout.page[2], 2, espw::WidgetText, 0, espw::AccentPeach);
  clearSlot(gState.layout.page[2], 3);

  gState.layout.page[3].template_id = espw::TemplateHeroPair;
  setSlot(gState.layout.page[3], 0, espw::WidgetPhoto, 0, espw::AccentSky);
  setSlot(gState.layout.page[3], 1, espw::WidgetClock, 0, espw::AccentPeach);
  setSlot(gState.layout.page[3], 2, espw::WidgetWeather, 0, espw::AccentSage);
  clearSlot(gState.layout.page[3], 3);

  gState.layout.page[4].template_id = espw::TemplateGrid;
  for (int i = 0; i < 4; ++i) clearSlot(gState.layout.page[4], i);
  setSlot(gState.layout.page[4], 3, espw::WidgetPhoneBattery, 0, espw::AccentSage);

  gState.settings.brightness = 200;
  gState.settings.sleep_sec = 60;
  gState.settings.rotation = 3;
  gState.settings.flags = 0b1000110;  // Celsius + WiFi enabled, 24-hour, no seconds
  gState.settings.current_page = 0;

  memset(&gState.location, 0, sizeof(gState.location));
  gState.location.lat_e6 = 19076000;   // Mumbai fallback; the app overwrites it.
  gState.location.lon_e6 = 72877700;
  strlcpy(gState.location.city, "Mumbai", sizeof(gState.location.city));
  memset(gState.texts, 0, sizeof(gState.texts));
  strlcpy(gState.texts[0], "Make something lovely today.", sizeof(gState.texts[0]));
}

void stateLoad() {
  espw::LayoutPayload saved{};
  if (gPrefs.getBytes("layout", &saved, sizeof(saved)) == sizeof(saved)) {
    if (saved.pages >= 1 && saved.pages <= 5 && saved.version >= 1) {
      bool ok = true;
      for (uint8_t p = 0; p < saved.pages && ok; ++p) {
        if (saved.page[p].template_id > espw::TemplateGrid) ok = false;
        for (uint8_t s = 0; s < 4; ++s) {
          auto& slot = saved.page[p].slots[s];
          if (slot.type == espw::WidgetSteps) slot.type = espw::WidgetBattery;
          if (slot.type != 0xFF && (!validWidget(slot.type) || !validAccent(slot.accent))) ok = false;
        }
      }
      if (ok) {
        gState.layout = saved;
        gState.layoutVersion = saved.version;
      }
    }
  }

  espw::SettingsPayload settings{};
  if (gPrefs.getBytes("settings", &settings, sizeof(settings)) == sizeof(settings)) {
    if (settings.brightness >= 10 && settings.brightness <= 255 &&
        settings.rotation <= 3 && settings.sleep_sec <= 240) {
      gState.settings = settings;
      if (gState.settings.current_page >= gState.layout.pages) gState.settings.current_page = 0;
    }
  }

  espw::WeatherPayload weather{};
  if (gPrefs.getBytes("weather", &weather, sizeof(weather)) == sizeof(weather)) {
    gState.weather = weather;
    gState.weatherValid = weather.updated != 0;
  }

  LocationConfig loc{};
  if (gPrefs.getBytes("loc", &loc, sizeof(loc)) == sizeof(loc) && loc.city[0] != 0) {
    gState.location = loc;
  }

  String ssid = gPrefs.getString("ssid", "");
  String pass = gPrefs.getString("pass", "");
  strlcpy(gState.wifiSsid, ssid.c_str(), sizeof(gState.wifiSsid));
  strlcpy(gState.wifiPassword, pass.c_str(), sizeof(gState.wifiPassword));
  gState.wifiConfigured = ssid.length() > 0;

  for (int i = 0; i < 4; ++i) {
    String key = String("text") + i;
    String value = gPrefs.getString(key.c_str(), i == 0 ? gState.texts[0] : "");
    strlcpy(gState.texts[i], value.c_str(), sizeof(gState.texts[i]));
  }

  gState.photoReady = LittleFS.exists("/photo.jpg");
  gState.battery.percent = 0;
  gState.battery.millivolts = 0;
  gState.battery.charging = 0;
  gState.battery.usb = 0;
  gState.battery.updated = 0;
  gState.dirty = true;
}

void stateSaveLayout() {
  gState.layout.version = ++gState.layoutVersion;
  gPrefs.putBytes("layout", &gState.layout, sizeof(gState.layout));
  gPrefs.putUInt("layout_v", gState.layoutVersion);
}

void stateSaveSettings() {
  gPrefs.putBytes("settings", &gState.settings, sizeof(gState.settings));
}

void stateSaveWifi() {
  gPrefs.putString("ssid", gState.wifiSsid);
  gPrefs.putString("pass", gState.wifiPassword);
  gState.wifiConfigured = gState.wifiSsid[0] != 0;
}

void stateSaveLocation() {
  gPrefs.putBytes("loc", &gState.location, sizeof(gState.location));
}

void stateSaveTexts() {
  for (int i = 0; i < 4; ++i) {
    String key = String("text") + i;
    gPrefs.putString(key.c_str(), gState.texts[i]);
  }
}

void stateSetToast(const char* text, uint32_t durationMs) {
  strlcpy(gState.toast, text ? text : "", sizeof(gState.toast));
  gState.toastUntil = millis() + durationMs;
  gState.dirty = true;
}
