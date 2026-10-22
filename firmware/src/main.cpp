#include <Arduino.h>
#include <LittleFS.h>
#include <esp_sleep.h>
#include <esp_task_wdt.h>

#include "app_state.h"
#include "ble.h"
#include "board_config.h"
#include "clock.h"
#include "display.h"
#include "net.h"
#include "power.h"
#include "sync.h"
#include "ui.h"

namespace {
uint32_t s_lastRender = 0;
uint32_t s_lastPower = 0;
bool s_lastUsb = false;
bool s_touchDown = false;
uint32_t s_pressStart = 0;
uint32_t s_lastRelease = 0;
bool s_longHandled = false;
bool s_screenOff = false;
int s_tapCount = 0;
uint32_t s_lastTapRelease = 0;

void handleTouch() {
  const bool down = digitalRead(PIN_TOUCH) == TOUCH_ACTIVE_LEVEL;
  const uint32_t now = millis();

  // 1. Initial Press
  if (down && !s_touchDown) {
    s_touchDown = true;
    s_pressStart = now;
    s_longHandled = false;
    gState.lastActivityMs = now;

    // If screen was turned off, pressing it once turns it back on!
    if (s_screenOff) {
      s_screenOff = false;
      s_longHandled = true;  // Consume this touch purely as screen wake-up
      s_tapCount = 0;
      gState.dirty = true;
      uiRender();
      displayFadeIn(gState.settings.brightness);
      return;
    }
  }

  // 2. Holding:
  // - 2 seconds: turns screen OFF
  if (down && !s_longHandled && !s_screenOff && (now - s_pressStart >= 2000)) {
    s_longHandled = true;
    s_screenOff = true;
    displayFadeOut(gState.settings.brightness);
    s_tapCount = 0;
    gState.lastActivityMs = now;
  }
  // - 6 seconds: Hardware Emergency REBOOT (no need to short battery terminals!)
  if (down && (now - s_pressStart >= 6000)) {
    s_longHandled = true;
    tft.setBrightness(gState.settings.brightness);
    uiRebootSplash();
    delay(500);
    ESP.restart();
  }

  // 3. Release
  if (!down && s_touchDown) {
    s_touchDown = false;
    const uint32_t duration = now - s_pressStart;
    s_lastRelease = now;

    if (s_longHandled) {
      return;
    }
    if (duration >= 20 && duration < 1000) {
      // Valid tap registered
      s_tapCount++;
      s_lastTapRelease = now;
      gState.lastActivityMs = now;
    }
  }

  // 4. Multi-tap processing (when finger is released and 350ms window elapses)
  if (s_tapCount > 0 && !s_touchDown && (now - s_lastTapRelease >= 350)) {
    const int taps = s_tapCount;
    s_tapCount = 0;

    if (taps == 4) {
      // Four taps: toggle Wi-Fi!
      net::toggle();
    } else if (taps == 1) {
      // Single tap: next page!
      uiNextPage(1);
      s_lastRender = millis();
    }
    gState.lastActivityMs = now;
  }
}

void handlePowerTransitions() {
  if (gState.usbPower != s_lastUsb) {
    s_lastUsb = gState.usbPower;
    if (gState.usbPower) {
      stateSetToast("Docked - WiFi enabled", 2200);
      gState.bleActive = true;
      ble::startAdvertising(0);
      net::begin();
      power::setBrightness(gState.settings.brightness);
    } else {
      stateSetToast("On battery - BLE only", 2200);
      net::stop();
    }
    gState.lastActivityMs = millis();
  }
}

void maybeSleep() {
  if (gState.usbPower) return;
  if (gState.settings.sleep_sec == 0) return;
  const uint32_t idle = millis() - gState.lastActivityMs;
  if (idle > 15000 && !s_screenOff && power::brightness() > 40 && gState.settings.sleep_sec > 20) {
    power::setBrightness(40);
  }
  if (idle > static_cast<uint32_t>(gState.settings.sleep_sec) * 1000UL && !s_screenOff) {
    s_screenOff = true;
    displayFadeOut(power::brightness());
  }
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(150);
  displayBegin();
  LittleFS.begin(true);
  stateBegin();
  pinMode(PIN_TOUCH, INPUT_PULLDOWN);
  power::begin();
  espclock::begin();
  uiBegin();

  const esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
  if (cause == ESP_SLEEP_WAKEUP_UNDEFINED) {
    uiSplash();
    displayFadeIn(gState.settings.brightness);
    delay(1000);
    displayFadeOut(gState.settings.brightness);
    uiClearAndRender();
    displayFadeIn(gState.settings.brightness);
  } else {
    tft.setBrightness(gState.settings.brightness);
    gState.dirty = true;
  }

  ble::begin();
  net::begin();
  s_lastUsb = gState.usbPower;
  gState.lastActivityMs = millis();
  s_lastPower = millis();

  esp_task_wdt_config_t twdt_config = {
      .timeout_ms = 8000,
      .idle_core_mask = (1 << 0),
      .trigger_panic = true,
  };
  esp_task_wdt_reconfigure(&twdt_config);
  esp_task_wdt_add(NULL);

  Serial.printf("[BOOT] ESP Widget %d.%d.%d, free heap %lu, depth %d\n", ESPW_VERSION_MAJOR,
                ESPW_VERSION_MINOR, ESPW_VERSION_PATCH,
                static_cast<unsigned long>(ESP.getFreeHeap()),
                static_cast<int>(canvas.getColorDepth()));
}

void loop() {
  esp_task_wdt_reset();
  handleTouch();
  if (gState.toast[0] && millis() > gState.toastUntil) {
    gState.toast[0] = 0;
    gState.dirty = true;
  }
  ble::loop();
  net::loop();
  if (millis() - s_lastPower > 2000) {
    s_lastPower = millis();
    power::update();
    handlePowerTransitions();
  }

  if (!s_screenOff) {
    const uint32_t now = millis();
    static int s_lastMinute = -1;
    const int currentMin = espclock::valid() ? (espclock::epochLocal() / 60) : -1;
    if (currentMin != s_lastMinute) {
      s_lastMinute = currentMin;
      gState.dirty = true;
    }

    const bool secondTick = uiNeedsSecondTick();
    const uint32_t interval = gState.pairingActive ? 500 : secondTick ? 1000 : 30000;
    if (gState.dirty || now - s_lastRender >= interval) {
      s_lastRender = now;
      uiRender();
    }
  }
  maybeSleep();
  delay(5);
}
