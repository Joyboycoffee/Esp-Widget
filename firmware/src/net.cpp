#include "net.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <math.h>
#include <NetworkClientSecure.h>
#include <WiFi.h>
#include <time.h>

#include "app_state.h"
#include "ble.h"
#include "clock.h"
#include "espw_protocol.h"

namespace net {
namespace {
bool s_scanRequested = false;
bool s_wantConnect = false;
bool s_weatherRequested = false;
uint32_t s_connectStarted = 0;
uint32_t s_lastWeather = 0;
uint32_t s_manualWindowUntil = 0;
uint32_t s_lastNtpAttempt = 0;

bool wifiSettingOn() { return (gState.settings.flags & 0x04) != 0; }

void publishState(uint8_t state) {
  if (gState.wifiState != state) {
    gState.wifiState = state;
    gState.dirty = true;
  }
}

void syncNtp() {
  if (millis() - s_lastNtpAttempt < 15000) return;
  s_lastNtpAttempt = millis();
  configTime(0, 0, "pool.ntp.org", "time.cloudflare.com");
}

void fetchWeather() {
  if (WiFi.status() != WL_CONNECTED) return;
  char url[320];
  snprintf(url, sizeof(url),
           "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f"
           "&current=temperature_2m,relative_humidity_2m,weather_code,is_day"
           "&hourly=temperature_2m&forecast_hours=12"
           "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
           "&forecast_days=3&timezone=auto",
           gState.location.lat_e6 / 1000000.0, gState.location.lon_e6 / 1000000.0);
  NetworkClientSecure client;
  client.setInsecure();
  client.setTimeout(4);
  HTTPClient http;
  http.setTimeout(4000);
  if (!http.begin(client, url)) {
    publishState(3);
    return;
  }
  const int code = http.GET();
  if (code != HTTP_CODE_OK) {
    Serial.printf("[NET] weather HTTP %d\n", code);
    http.end();
    publishState(3);
    return;
  }
  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, http.getString());
  http.end();
  if (err) {
    Serial.printf("[NET] weather JSON: %s\n", err.c_str());
    publishState(3);
    return;
  }
  auto cur = doc["current"];
  auto daily = doc["daily"];
  auto hourly = doc["hourly"]["temperature_2m"];
  espw::WeatherPayload w{};
  w.temp_c10 = static_cast<int16_t>(lroundf(cur["temperature_2m"].as<float>() * 10.0f));
  w.humidity = static_cast<uint8_t>(cur["relative_humidity_2m"].as<int>());
  w.code = static_cast<uint8_t>(cur["weather_code"].as<int>());
  w.is_day = static_cast<uint8_t>(cur["is_day"].as<int>());
  w.hi_c10 = static_cast<int16_t>(lroundf(daily["temperature_2m_max"][0].as<float>() * 10.0f));
  w.lo_c10 = static_cast<int16_t>(lroundf(daily["temperature_2m_min"][0].as<float>() * 10.0f));
  w.precip = static_cast<uint8_t>(daily["precipitation_probability_max"][0].as<int>());
  w.trend_count = 0;
  for (JsonVariant value : hourly.as<JsonArray>()) {
    if (w.trend_count >= 12) break;
    w.trend_c10[w.trend_count++] = static_cast<int16_t>(lroundf(value.as<float>() * 10.0f));
  }
  w.updated = espclock::epochUtc();
  if (!w.updated) w.updated = static_cast<uint32_t>(time(nullptr));
  strlcpy(w.city, gState.location.city, sizeof(w.city));
  gState.weather = w;
  gState.weatherValid = true;
  gState.dirty = true;
  gPrefs.putBytes("weather", &w, sizeof(w));
  s_lastWeather = millis();
  publishState(2);
  Serial.printf("[NET] weather %.1f C (code %u)\n", w.temp_c10 / 10.0f, w.code);
}

void sendScanResults(int count) {
  espw::WifiScanResult result{};
  if (count > 8) count = 8;
  result.count = count < 0 ? 0 : static_cast<uint8_t>(count);
  for (int i = 0; i < result.count; ++i) {
    String ssid = WiFi.SSID(i);
    strlcpy(result.aps[i].ssid, ssid.c_str(), sizeof(result.aps[i].ssid));
    result.aps[i].rssi = static_cast<int8_t>(WiFi.RSSI(i));
    result.aps[i].auth = WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? 0 : 1;
  }
  WiFi.scanDelete();
  ble::send(static_cast<uint8_t>(espw::MsgType::WifiScanResult), &result, sizeof(result));
}
}  // namespace

void begin() {
  WiFi.mode(WIFI_OFF);
  WiFi.setSleep(true);
  publishState(0);
  if (gState.wifiConfigured && gState.usbPower && wifiSettingOn()) {
    s_wantConnect = true;
    s_connectStarted = millis();
  }
}

void requestScan() {
  s_scanRequested = true;
  s_manualWindowUntil = millis() + 120000;
}

void setCredentials(const char* ssid, const char* password, bool connectNow) {
  strlcpy(gState.wifiSsid, ssid ? ssid : "", sizeof(gState.wifiSsid));
  strlcpy(gState.wifiPassword, password ? password : "", sizeof(gState.wifiPassword));
  gState.wifiConfigured = gState.wifiSsid[0] != 0;
  stateSaveWifi();
  s_wantConnect = connectNow && gState.wifiConfigured;
  s_connectStarted = millis();
  s_manualWindowUntil = millis() + 120000;
  gState.dirty = true;
}

void forget() {
  gState.wifiSsid[0] = 0;
  gState.wifiPassword[0] = 0;
  gState.wifiConfigured = false;
  stateSaveWifi();
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  publishState(0);
  s_wantConnect = false;
  gState.dirty = true;
}

void stop() {
  s_wantConnect = false;
  s_scanRequested = false;
  s_weatherRequested = false;
  s_manualWindowUntil = 0;
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  publishState(0);
}

bool isEnabled() {
  return wifiSettingOn() || s_wantConnect || WiFi.status() == WL_CONNECTED;
}

void toggle() {
  const bool turningOn = !isEnabled();
  if (turningOn) {
    gState.settings.flags |= 0x04;
    stateSaveSettings();
    s_manualWindowUntil = millis() + 300000;
    s_wantConnect = gState.wifiConfigured;
    s_connectStarted = millis();
    WiFi.mode(WIFI_STA);
    if (gState.wifiConfigured) {
      WiFi.begin(gState.wifiSsid, gState.wifiPassword);
      publishState(1);
      stateSetToast("Wi-Fi: ON (Connecting)", 2500);
    } else {
      publishState(1);
      stateSetToast("Wi-Fi: ON (Set SSID in app)", 2500);
    }
  } else {
    gState.settings.flags &= ~0x04;
    stateSaveSettings();
    s_wantConnect = false;
    s_manualWindowUntil = 0;
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
    publishState(0);
    stateSetToast("Wi-Fi: OFF", 2500);
  }
  gState.dirty = true;
  gState.lastActivityMs = millis();
}

void requestWeather() {
  s_weatherRequested = true;
  s_manualWindowUntil = millis() + 120000;
  if (WiFi.status() == WL_CONNECTED) {
    fetchWeather();
    s_weatherRequested = false;
  }
}

bool connected() { return WiFi.status() == WL_CONNECTED; }

void loop() {
  const uint32_t now = millis();
  const bool manual = now < s_manualWindowUntil;
  const bool allowed = (gState.usbPower || manual) && (wifiSettingOn() || manual);

  if (s_scanRequested) {
    s_scanRequested = false;
    if (!allowed) {
      publishState(0);
    } else {
      WiFi.mode(WIFI_STA);
      WiFi.disconnect(false, false);
      const int found = WiFi.scanNetworks();
      sendScanResults(found);
      if (!s_wantConnect && WiFi.status() != WL_CONNECTED) {
        WiFi.mode(WIFI_OFF);
      }
    }
  }

  if ((!allowed || !wifiSettingOn()) && WiFi.status() != WL_CONNECTED && !s_wantConnect) {
    if (gState.wifiState != 0) {
      WiFi.disconnect(true);
      WiFi.mode(WIFI_OFF);
      publishState(0);
    }
  }

  if (!wifiSettingOn() && !manual && WiFi.status() == WL_CONNECTED) {
    WiFi.disconnect(true);
    WiFi.mode(WIFI_OFF);
    publishState(0);
    s_wantConnect = false;
  }

  if (s_wantConnect && gState.wifiConfigured && allowed && wifiSettingOn()) {
    if (WiFi.status() != WL_CONNECTED) {
      if (gState.wifiState == 2) publishState(0);
      if (gState.wifiState != 1) {
        WiFi.mode(WIFI_STA);
        WiFi.begin(gState.wifiSsid, gState.wifiPassword);
        publishState(1);
        s_connectStarted = now;
      }
      if (now - s_connectStarted > 15000) {
        publishState(3);
        s_wantConnect = false;
      }
    } else {
      if (gState.wifiState != 2) {
        publishState(2);
        gState.wifiRssi = static_cast<int8_t>(WiFi.RSSI());
        strlcpy(gState.ip, WiFi.localIP().toString().c_str(), sizeof(gState.ip));
        syncNtp();
        s_weatherRequested = true;
      }
      gState.wifiRssi = static_cast<int8_t>(WiFi.RSSI());
    }
  } else if (WiFi.status() == WL_CONNECTED) {
    gState.wifiRssi = static_cast<int8_t>(WiFi.RSSI());
  }

  if (WiFi.status() == WL_CONNECTED) {
    const time_t ntpNow = time(nullptr);
    if (ntpNow > 1600000000 && !espclock::valid()) {
      espclock::sync(static_cast<uint32_t>(ntpNow), 0, false);
    }
  }

  if (s_weatherRequested && WiFi.status() == WL_CONNECTED &&
      (s_lastWeather == 0 || now - s_lastWeather > 15UL * 60UL * 1000UL)) {
    s_weatherRequested = false;
    fetchWeather();
  }
}
}  // namespace net
