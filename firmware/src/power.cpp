#include "power.h"

#include <NimBLEDevice.h>
#include <WiFi.h>
#include <driver/gpio.h>
#include <esp_sleep.h>
#include <soc/usb_serial_jtag_reg.h>
#include <soc/soc.h>

#include "app_state.h"
#include "board_config.h"
#include "clock.h"
#include "display.h"
#include "theme.h"

namespace power {
namespace {
uint8_t s_brightness = 200;
bool s_lowBattery = false;
Diag s_diag;

// A connected USB host sends a start-of-frame every millisecond, so the frame
// counter of the built-in USB controller keeps changing while a computer is
// attached. A plain wall charger never moves it.
bool usbHostPresent() {
  static uint32_t last = 0xFFFFFFFF;
  static uint8_t stale = 0;
  const uint32_t now = REG_READ(USB_SERIAL_JTAG_FRAM_NUM_REG) & USB_SERIAL_JTAG_SOF_FRAME_INDEX;
  if (now != last) {
    last = now;
    stale = 0;
    return true;
  }
  if (stale < 255) stale++;
  return stale < 2;  // tolerate one identical sample
}

struct ChargerSense {
  bool charging;
  bool full;
  bool docked;
  bool wired;
  int red;
  int blue;
};

int lipoPercent(float v) {
  if (v >= 4.18f) return 100;
  if (v >= 4.05f) return 88 + static_cast<int>((v - 4.05f) / 0.13f * 12.0f);
  if (v >= 3.88f) return 68 + static_cast<int>((v - 3.88f) / 0.17f * 20.0f);
  if (v >= 3.75f) return 45 + static_cast<int>((v - 3.75f) / 0.13f * 23.0f);
  if (v >= 3.60f) return 20 + static_cast<int>((v - 3.60f) / 0.15f * 25.0f);
  if (v >= 3.40f) return 5 + static_cast<int>((v - 3.40f) / 0.20f * 15.0f);
  return max(0, static_cast<int>((v - 3.00f) / 0.40f * 5.0f));
}

// The stock TP4056 LEDs have their cathodes on GPIO20/21. They are sampled
// high-impedance and only pulsed low for a few microseconds so the pins never
// back-feed the charger or make the LEDs glow faintly.
ChargerSense sampleCharger() {
  ChargerSense out{false, false, false, false, 0, 0};
  gpio_pulldown_en(static_cast<gpio_num_t>(PIN_TP_BLUE));
  gpio_pulldown_en(static_cast<gpio_num_t>(PIN_TP_RED));
  delayMicroseconds(30);
  const int blue = gpio_get_level(static_cast<gpio_num_t>(PIN_TP_BLUE));
  const int red = gpio_get_level(static_cast<gpio_num_t>(PIN_TP_RED));
  gpio_pulldown_dis(static_cast<gpio_num_t>(PIN_TP_BLUE));
  gpio_pulldown_dis(static_cast<gpio_num_t>(PIN_TP_RED));

  // charging: red cathode low, blue high. full: red high, blue low.
  // docked/powered via TP4056: either LED pin is high (pulled down to 0 when unpowered on battery)
  out.red = red;
  out.blue = blue;
  out.charging = (red == 0 && blue == 1);
  out.full = (red == 1 && blue == 0);
  out.docked = (red == 1 || blue == 1);
  out.wired = out.docked;
  return out;
}

void configureChargerPins() {
  gpio_reset_pin(static_cast<gpio_num_t>(PIN_TP_BLUE));
  gpio_reset_pin(static_cast<gpio_num_t>(PIN_TP_RED));
  gpio_config_t cfg = {};
  cfg.pin_bit_mask = (1ULL << PIN_TP_BLUE) | (1ULL << PIN_TP_RED);
  cfg.mode = GPIO_MODE_INPUT;
  cfg.pull_up_en = GPIO_PULLUP_DISABLE;
  cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
  cfg.intr_type = GPIO_INTR_DISABLE;
  gpio_config(&cfg);
}
}  // namespace

// Only touches the backlight when the value actually changes, and never while
// the screen is intentionally dark (sleep / long-press off): the next fade-in
// picks up brightness() anyway.
const Diag& diag() { return s_diag; }

void applyBrightness() {
  if (tft.getBrightness() == 0) return;
  const uint8_t target = brightness();
  if (target == tft.getBrightness()) return;
  tft.setBrightness(target);
}

void begin() {
  analogSetPinAttenuation(PIN_BAT_ADC, ADC_11db);
  pinMode(PIN_BAT_ADC, INPUT);
  configureChargerPins();
  gState.battery.percent = 0;
  gState.battery.millivolts = 0;
  gState.battery.charging = 0;
  gState.battery.usb = 0;
  s_brightness = gState.settings.brightness;
  s_lowBattery = false;
  update();
}

void update() {
  uint32_t rawSum = 0;
  for (int i = 0; i < 24; ++i) {
    rawSum += analogReadMilliVolts(PIN_BAT_ADC);
    delayMicroseconds(50);
  }
  const float rawMv = rawSum / 24.0f;
  const float multiplier = gPrefs.getFloat("bat_cal", 2.16f);
  const float vbat = rawMv * multiplier / 1000.0f;
  const ChargerSense charger = sampleCharger();

  // The ESP32's own USB port pulls the divider high (>= 2180 mV); a missing cell reads < 80 mV.
  // Both cases mean we are externally powered via the ESP32 Type-C port.
  const bool espUsb = (rawMv >= 2180.0f) || (rawMv < 80.0f);
  const bool host = usbHostPresent();
  const bool rawUsb = espUsb || charger.docked || host;

  // Debounce USB state transitions to prevent floating ADC/charger spikes
  // from falsely toggling docked/on-battery toasts.
  static bool s_debouncedUsb = false;
  static uint8_t s_usbDebounceCount = 0;
  static bool s_usbInitialized = false;
  if (!s_usbInitialized) {
    s_debouncedUsb = rawUsb;
    s_usbInitialized = true;
  } else if (rawUsb != s_debouncedUsb) {
    // Three agreeing samples (~6 s) before we believe a dock/undock. A single
    // noisy ADC/charger-LED reading used to toggle Wi-Fi and brightness.
    if (++s_usbDebounceCount >= 3) {
      s_debouncedUsb = rawUsb;
      s_usbDebounceCount = 0;
    }
  } else {
    s_usbDebounceCount = 0;
  }
  const bool usb = s_debouncedUsb;

  const bool voltageValid = vbat >= 2.60f && vbat <= 4.45f;
  s_diag.rawMv = rawMv;
  s_diag.vbat = vbat;
  s_diag.voltageValid = voltageValid;
  s_diag.ledRed = charger.red;
  s_diag.ledBlue = charger.blue;
  s_diag.byAdc = espUsb;
  s_diag.byLed = charger.docked;
  s_diag.byHost = host;
  const uint8_t previousPercent = gState.battery.percent;
  const uint16_t previousMv = gState.battery.millivolts;

  uint8_t percent = previousPercent;
  uint16_t millivolts = previousMv;
  if (charger.full) {
    percent = 100;
  } else if (voltageValid) {
    percent = static_cast<uint8_t>(constrain(lipoPercent(vbat), 0, 100));
  } else if (usb && previousPercent == 0 && previousMv == 0) {
    // No usable cell reading yet. Do not claim a full battery just because
    // USB is present; show the last known value once one exists.
    percent = 0;
  }
  if (voltageValid) millivolts = static_cast<uint16_t>(vbat * 1000.0f);

  // Without a valid cell voltage we cannot tell, so don't claim "charging".
  const bool charging = charger.charging ||
                        (usb && voltageValid && !charger.full && !charger.wired && millivolts < 4150);
  const bool full = charger.full || (usb && !charging && percent >= 100);

  // Require at least a 30mV change or a percent change to mark dirty.
  // This filters out ADC noise and prevents constant 2-second screen redraws.
  const bool mvChanged = abs(static_cast<int>(millivolts) - static_cast<int>(previousMv)) >= 30;
  const bool changed = (percent != previousPercent) ||
                       mvChanged ||
                       ((charging ? 1 : 0) != gState.battery.charging) ||
                       ((usb ? 1 : 0) != gState.battery.usb) ||
                       (usb != gState.usbPower);

  gState.usbPower = usb;
  gState.charging = charging;
  gState.chargeFull = full;
  gState.battery.percent = percent;
  gState.battery.millivolts = millivolts;
  gState.battery.charging = charging ? 1 : 0;
  gState.battery.usb = usb ? 1 : 0;
  gState.battery.updated = espclock::epochUtc();
  if (changed) {
    gState.dirty = true;
    gState.statusDirty = true;
  }

  // Low-battery dimming with hysteresis: dim below 5 %, only undim above 10 %
  // (or when docked). Readings without a valid voltage never trigger it.
  bool low = s_lowBattery;
  if (usb) low = false;
  else if (voltageValid && percent <= 5) low = true;
  else if (voltageValid && percent >= 10) low = false;
  if (low != s_lowBattery) {
    s_lowBattery = low;
    applyBrightness();
  }
}

void setBrightness(uint8_t value) {
  s_brightness = value;
  applyBrightness();
}

uint8_t brightness() {
  return s_lowBattery && s_brightness > 60 ? 60 : s_brightness;
}

void enterDeepSleep() {
  Serial.println("[POWER] entering deep sleep");
  // Deep sleep powers the radios down by itself. Tearing NimBLE/Wi-Fi down by
  // hand first was what panicked (reset 4), so it is deliberately not done.
  gState.bleActive = false;

  // The button wakes the chip by going HIGH. If its pin floats while asleep
  // (internal pull-downs are dropped in deep sleep unless held) the chip wakes
  // again straight away. So: wait until the button is released, then latch the
  // pull-down so the pin stays firmly LOW until it is really pressed.
  const gpio_num_t touch = static_cast<gpio_num_t>(PIN_TOUCH);
  pinMode(PIN_TOUCH, INPUT_PULLDOWN);
  const uint32_t t0 = millis();
  while (digitalRead(PIN_TOUCH) == TOUCH_ACTIVE_LEVEL && millis() - t0 < 4000) delay(10);
  delay(150);
  gpio_pullup_dis(touch);
  gpio_pulldown_en(touch);
  gpio_hold_en(touch);
  esp_deep_sleep_enable_gpio_wakeup(1ULL << PIN_TOUCH, ESP_GPIO_WAKEUP_GPIO_HIGH);

  displaySleepHold();  // backlight/reset/DC latched off; also enables the global deep-sleep hold
  delay(80);
  esp_deep_sleep_start();
}

}  // namespace power
