#pragma once
#include <Arduino.h>

namespace espsync {
void handleFrame(uint8_t type, const uint8_t* payload, uint16_t len, uint16_t seq);
void sendHello();
void sendStatus();
void sendAck(uint16_t seq, uint8_t status);
void sendNack(uint16_t seq, uint8_t reason);
void sendTime();
void sendBattery();
}  // namespace espsync
