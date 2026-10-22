#pragma once
#include <stdint.h>

constexpr uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b) {
  return static_cast<uint16_t>(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

constexpr uint16_t C_BG = rgb565(247, 243, 236);
constexpr uint16_t C_CARD = rgb565(255, 253, 248);
constexpr uint16_t C_INK = rgb565(58, 53, 64);
constexpr uint16_t C_MUTED = rgb565(139, 132, 146);
constexpr uint16_t C_LINE = rgb565(231, 224, 214);
constexpr uint16_t C_SHADOW = rgb565(220, 212, 202);
constexpr uint16_t C_WHITE = rgb565(255, 255, 255);
constexpr uint16_t C_PEACH = rgb565(243, 183, 164);
constexpr uint16_t C_SAGE = rgb565(169, 198, 167);
constexpr uint16_t C_SKY = rgb565(175, 205, 234);
constexpr uint16_t C_LAVENDER = rgb565(201, 188, 232);
constexpr uint16_t C_BUTTER = rgb565(246, 217, 139);
constexpr uint16_t C_ROSE = rgb565(239, 175, 195);
constexpr uint16_t C_DANGER = rgb565(214, 112, 112);
constexpr uint16_t C_GOOD = rgb565(112, 164, 124);
constexpr uint16_t C_SUN = rgb565(230, 178, 92);
constexpr uint16_t C_RAIN = rgb565(126, 164, 203);

constexpr int UI_MARGIN = 9;
constexpr int UI_HEADER_H = 20;
constexpr int UI_GAP = 7;
constexpr int UI_HERO_H = 104;
constexpr int UI_SMALL_H = 92;
constexpr int UI_CONTENT_Y = 22;

inline uint16_t mixColor(uint16_t a, uint16_t b, uint8_t amountOfA) {
  const int r = (((a >> 11) & 0x1F) * amountOfA + ((b >> 11) & 0x1F) * (100 - amountOfA)) / 100;
  const int g = (((a >> 5) & 0x3F) * amountOfA + ((b >> 5) & 0x3F) * (100 - amountOfA)) / 100;
  const int bl = ((a & 0x1F) * amountOfA + (b & 0x1F) * (100 - amountOfA)) / 100;
  return static_cast<uint16_t>((r << 11) | (g << 5) | bl);
}

inline uint16_t accentColor(uint8_t accent) {
  switch (accent) {
    case 0: return C_PEACH;
    case 1: return C_SAGE;
    case 2: return C_SKY;
    case 3: return C_LAVENDER;
    case 4: return C_BUTTER;
    case 5: return C_ROSE;
    default: return C_PEACH;
  }
}
