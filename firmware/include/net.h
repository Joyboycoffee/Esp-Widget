#pragma once
#include <Arduino.h>

namespace net {
void begin();
void loop();
void requestScan();
void setCredentials(const char* ssid, const char* password, bool connectNow);
void forget();
void requestWeather();
void stop();
void toggle();
bool isEnabled();
bool connected();
}  // namespace net
