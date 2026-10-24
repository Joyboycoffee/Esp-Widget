#pragma once
#include <Arduino.h>

#include "espw_protocol.h"

namespace ble {
void begin();
void loop();
void startAdvertising(uint32_t windowMs = 0);
void stopAdvertising();
void disconnect();
void send(uint8_t type, const void* payload, uint16_t len);
bool connected();
const char* deviceName();
void clearBonds();
// Screen off: stretch the connection interval so the radio wakes far less often.
void setLowPower(bool low);
}  // namespace ble
