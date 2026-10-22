#pragma once
#include <Arduino.h>

namespace power {
// Raw readings behind the USB / battery decisions, shown on the diagnostics page.
struct Diag {
  float rawMv = 0;
  float vbat = 0;
  bool voltageValid = false;
  int ledRed = 0;
  int ledBlue = 0;
  bool byAdc = false;   // ADC pin high/open => powered through the ESP32 USB port
  bool byLed = false;   // charger LED pins show a charger is attached
  bool byHost = false;  // a USB host is talking to the ESP32 (SOF frames)
};
const Diag& diag();
void begin();
void update();
void enterDeepSleep();
void setBrightness(uint8_t value);
void applyBrightness();
uint8_t brightness();
}  // namespace power
