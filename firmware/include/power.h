#pragma once
#include <Arduino.h>

namespace power {
void begin();
void update();
void enterDeepSleep();
void setBrightness(uint8_t value);
uint8_t brightness();
}  // namespace power
