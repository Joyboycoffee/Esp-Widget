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
#include "version.h"

// HTTPS (weather) + JSON + UI string buffers do not fit comfortably in the
// default 8 KB loop stack; overflowing it was one source of random resets.
SET_LOOP_TASK_STACK_SIZE(16 * 1024);

namespace {
constexpr uint32_t kDeepSleepAfterOffMs = 10UL * 60UL * 1000UL;  // screen off this long on battery -> deep sleep

uint32_t s_lastRender = 0;
uint32_t s_lastHeader = 0;
uint32_t s_lastPower = 0;
bool s_lastUsb = false;
bool s_touchDown = false;
uint32_t s_pressStart = 0;
bool s_longHandled = false;
bool s_screenOff = false;
uint32_t s_screenOffSince = 0;
int s_tapCount = 0;
uint32_t s_reinitAt = 0;   // panel re-init after a charger plug/unplug
uint32_t s_netStartAt = 0; // Wi-Fi start, delayed until the supply has settled
uint32_t s_lastTapRelease = 0;

void screenOff() {
  if (s_screenOff) return;
  s_screenOff = true;
  s_screenOffSince = millis();
  displayFadeOut(power::brightness());
  tft.sleep();             // panel controller into sleep mode (saves a few mA)
  ble::setLowPower(true);  // radio wakes ~4x/s instead of ~25x/s
}

void screenOn() {
  if (!s_screenOff) return;
  s_screenOff = false;
  // A full controller re-init is more reliable than a bare sleep-out: a
  // missed wake command left the screen black until a reboot.
  displayReinit();
  ble::setLowPower(false);
  uiRender();  // paint the current content while the backlight is still dark
  displayFadeIn(power::brightness());
}

// Commands from the phone (wake the screen / deep sleep). Run here, in loop(),
// so the BLE callback never blocks and the ACK has already been sent.
void handleRemoteCommands() {
  if (gState.wakeRequested) {
    gState.wakeRequested = false;
    gState.lastActivityMs = millis();
    if (s_screenOff) screenOn();
  }
  if (gState.screenOffRequested) {
    gState.screenOffRequested = false;
    screenOff();
  }
  if (gState.pageRequested >= 0) {
    const int page = gState.pageRequested;
    gState.pageRequested = -1;
    if (!s_screenOff) {
      uiSetPage(page);
      s_lastRender = millis();
    }
  }
  if (gState.sleepRequested && static_cast<int32_t>(millis() - gState.sleepAt) >= 0) {
    gState.sleepRequested = false;
    if (gState.pageSaveAt) stateSaveSettings();
    if (!s_screenOff) {
      uiSleepSplash();
      delay(1400);
      displayFadeOut(power::brightness());
    }
    power::enterDeepSleep();  // radios off; only the touch button wakes the widget
  }
}

void handleTouch() {
  const bool down = digitalRead(PIN_TOUCH) == TOUCH_ACTIVE_LEVEL;
  const uint32_t now = millis();

  if (down && !s_touchDown) {
    s_touchDown = true;
    s_pressStart = now;
    s_longHandled = false;
    gState.lastActivityMs = now;
    if (s_screenOff) {
      // A touch on a dark screen only wakes it; it never changes the page.
      s_longHandled = true;
      s_tapCount = 0;
      screenOn();
      return;
    }
  }

  // Hold 2 s: screen off.  Hold 6 s: restart.
  if (down && !s_longHandled && !s_screenOff && (now - s_pressStart >= 2000)) {
    s_longHandled = true;
    s_tapCount = 0;
    screenOff();
  }
  if (down && (now - s_pressStart >= 6000)) {
    tft.setBrightness(power::brightness());
    uiRebootSplash();
    delay(500);
    ESP.restart();
  }

  if (!down && s_touchDown) {
    s_touchDown = false;
    const uint32_t duration = now - s_pressStart;
    if (s_longHandled) return;
    if (duration >= 25 && duration < 1000) {
      s_tapCount++;
      s_lastTapRelease = now;
      gState.lastActivityMs = now;
    }
  }

  // Taps are counted for 350 ms after the last release.
  if (s_tapCount > 0 && !s_touchDown && (now - s_lastTapRelease >= 350)) {
    const int taps = s_tapCount;
    s_tapCount = 0;
    if (uiDiagActive()) {
      uiToggleDiag();  // any tap closes the diagnostics page
    } else if (taps == 3) {
      uiToggleDiag();  // triple tap opens it
    } else if (taps >= 4) {
      net::toggle();
    } else if (taps == 1) {
      uiNextPage(1);
      s_lastRender = millis();
    } else if (taps == 2) {
      uiNextPage(-1);
      s_lastRender = millis();
    }
    gState.lastActivityMs = now;
  }
}

void handlePowerTransitions() {
  if (gState.usbPower == s_lastUsb) return;
  s_lastUsb = gState.usbPower;
  if (gState.usbPower) {
    stateSetToast("Docked - Wi-Fi allowed", 2200);
    gState.bleActive = true;
    ble::startAdvertising(0);
    s_netStartAt = millis() + 5000;  // let the supply settle before the Wi-Fi radio draws current
  } else {
    s_netStartAt = 0;
    stateSetToast("On battery - Bluetooth only", 2200);
    net::stop();
  }
  s_reinitAt = millis() + 1200;
  gState.lastActivityMs = millis();
}

void maybeSleep() {
  if (gState.usbPower) return;
  const uint32_t now = millis();
  if (!s_screenOff && gState.settings.sleep_sec != 0 &&
      now - gState.lastActivityMs > static_cast<uint32_t>(gState.settings.sleep_sec) * 1000UL) {
    screenOff();
  }
  // Screen has been dark for a long time on battery: power everything down.
  // A touch wakes the widget again (it reboots and reconnects in ~1 s).
  if (s_screenOff && !s_touchDown && now - s_screenOffSince > kDeepSleepAfterOffMs) {
    if (gState.pageSaveAt) stateSaveSettings();
    power::enterDeepSleep();
  }
}

void lowerCpuClock() {
  // 80 MHz is plenty for this UI, keeps BLE and Wi-Fi fully working and
  // noticeably lowers idle current versus the default 160 MHz. Done before
  // the radios start so their timing is configured for the final clock.
  setCpuFrequencyMhz(80);
  Serial.printf("[POWER] CPU %lu MHz\n", static_cast<unsigned long>(getCpuFrequencyMhz()));
}
}  // namespace

void setup() {
  Serial.begin(115200);
  delay(50);
  displayBegin();
  LittleFS.begin(true);
  stateBegin();
  pinMode(PIN_TOUCH, INPUT_PULLDOWN);
  power::begin();
  espclock::begin();
  uiBegin();  // allocate the draw buffer before BLE/Wi-Fi fragment the heap

  const esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();
  if (cause == ESP_SLEEP_WAKEUP_UNDEFINED) {
    uiSplash();
    displayFadeIn(power::brightness());
    delay(900);
    displayFadeOut(power::brightness());
    uiRender();
    displayFadeIn(power::brightness());
  } else {
    // Woken by touch: the finger is probably still down. Swallow that press
    // so the wake-up tap does not also flip the page.
    s_touchDown = digitalRead(PIN_TOUCH) == TOUCH_ACTIVE_LEVEL;
    s_longHandled = true;
    s_pressStart = millis();
    uiRender();
    displayFadeIn(power::brightness());
  }

  {
    // Tell the user when the last restart was not a normal one, so a crash
    // can be told apart from the screen simply being off.
    const esp_reset_reason_t why = esp_reset_reason();
    if (why == ESP_RST_BROWNOUT) stateSetToast("Restarted: power dip (brownout)", 6000);
    else if (why == ESP_RST_PANIC) stateSetToast("Restarted after a crash", 6000);
    else if (why == ESP_RST_TASK_WDT || why == ESP_RST_INT_WDT || why == ESP_RST_WDT)
      stateSetToast("Restarted: firmware froze", 6000);
  }
  lowerCpuClock();
  ble::begin();
  net::begin();
  s_lastUsb = gState.usbPower;
  gState.lastActivityMs = millis();
  s_lastPower = millis();

  // 30 s is long enough for a slow TLS handshake but still catches real hangs.
  esp_task_wdt_config_t twdt_config = {
      .timeout_ms = 30000,
      .idle_core_mask = 0,
      .trigger_panic = true,
  };
  esp_task_wdt_reconfigure(&twdt_config);
  esp_task_wdt_add(NULL);

  Serial.printf("[BOOT] %s %d.%d.%d build %d, free heap %lu, reset reason %d\n", ESPW_FW_NAME, ESPW_VERSION_MAJOR,
                ESPW_VERSION_MINOR, ESPW_VERSION_PATCH, ESPW_BUILD,
                static_cast<unsigned long>(ESP.getFreeHeap()), static_cast<int>(esp_reset_reason()));
}

void loop() {
  esp_task_wdt_reset();
  handleTouch();
  handleRemoteCommands();
  if (gState.toast[0] && millis() > gState.toastUntil) {
    gState.toast[0] = 0;
    gState.dirty = true;
  }
  ble::loop();
  net::loop();
  if (millis() - s_lastPower > (s_screenOff ? 10000UL : 2000UL)) {
    s_lastPower = millis();
    power::update();
    handlePowerTransitions();
  }
  if (s_netStartAt && static_cast<int32_t>(millis() - s_netStartAt) >= 0) {
    s_netStartAt = 0;
    if (gState.usbPower) net::begin();
  }
  if (s_reinitAt && static_cast<int32_t>(millis() - s_reinitAt) >= 0) {
    s_reinitAt = 0;
    displayReinit();
    if (s_screenOff) {
      tft.sleep();
    } else {
      uiRender();
      tft.setBrightness(power::brightness());
    }
  }
  if (gState.pageSaveAt && static_cast<int32_t>(millis() - gState.pageSaveAt) >= 0) {
    gState.pageSaveAt = 0;
    stateSaveSettings();
  }

  if (!s_screenOff) {
    const uint32_t now = millis();
    static int s_lastMinute = -1;
    const int currentMin = espclock::valid() ? (espclock::epochLocal() / 60) : -1;
    if (currentMin != s_lastMinute) {
      s_lastMinute = currentMin;
      gState.dirty = true;
    }

    const uint32_t interval = uiNeedsSecondTick() ? 1000 : 30000;
    static uint32_t s_lastAnim = 0;
    if (uiNeedsAnimTick() && !gState.dirty && now - s_lastAnim >= 16 && now - s_lastRender < interval) {
      s_lastAnim = now;
      uiAnimFrame();  // partial redraw of just the charging battery
    } else if (gState.dirty || now - s_lastRender >= interval) {
      s_lastRender = now;
      s_lastHeader = now;
      uiRender();
    } else if (uiHeaderAnimating() && now - s_lastHeader >= 500) {
      // Only the status bar blinks; redrawing just that strip is cheap.
      s_lastHeader = now;
      uiRenderHeader();
    }
  }
  maybeSleep();
  delay(s_screenOff ? 60 : 8);
}
