#pragma once
#include <Arduino.h>
#include <Preferences.h>

#include "espw_protocol.h"

struct LocationConfig {
  int32_t lat_e6;
  int32_t lon_e6;
  char city[16];
};

struct RuntimeState {
  espw::LayoutPayload layout{};
  espw::SettingsPayload settings{};
  espw::BatteryPayload battery{};
  espw::WeatherPayload weather{};
  bool weatherValid = false;
  espw::StockPayload stocks[6]{};
  bool stockValid[6]{};
  espw::NotificationPayload notification{};
  bool notificationValid = false;
  espw::MediaPayload media{};
  bool mediaValid = false;
  espw::CalendarPayload calendar{};
  bool calendarValid = false;
  espw::StepsPayload steps{};
  bool stepsValid = false;
  char texts[4][96]{};
  LocationConfig location{};
  char wifiSsid[33]{};
  char wifiPassword[65]{};
  bool wifiConfigured = false;
  bool wifiEnabled = true;

  bool usbPower = false;
  bool charging = false;
  bool chargeFull = false;
  bool bleActive = false;
  bool bleConnected = false;
  bool pairingActive = false;
  uint32_t pairingPasskey = 0;
  uint8_t wifiState = 0;  // 0 off, 1 connecting, 2 connected, 3 error
  int8_t wifiRssi = 0;
  char ip[16]{};
  bool photoReady = false;
  bool dirty = true;
  uint32_t layoutVersion = 1;
  uint32_t lastActivityMs = 0;
  char toast[48]{};
  uint32_t toastUntil = 0;
  uint8_t touchCount = 0;
};

extern RuntimeState gState;
extern Preferences gPrefs;

void stateBegin();
void stateLoad();
void stateSaveLayout();
void stateSaveSettings();
void stateSaveWifi();
void stateSaveLocation();
void stateSaveTexts();
void stateDefaultLayout();
void stateSetToast(const char* text, uint32_t durationMs = 1800);

inline uint8_t statePageCount() {
  return gState.layout.pages < 1 ? 1 : (gState.layout.pages > 5 ? 5 : gState.layout.pages);
}
inline uint8_t stateCurrentPage() {
  return gState.settings.current_page < statePageCount() ? gState.settings.current_page : 0;
}
