#pragma once
#include <LovyanGFX.hpp>

namespace icons {
void sun(LovyanGFX& g, int x, int y, int s, uint16_t color);
void cloud(LovyanGFX& g, int x, int y, int s, uint16_t color);
void rain(LovyanGFX& g, int x, int y, int s, uint16_t color);
void snow(LovyanGFX& g, int x, int y, int s, uint16_t color);
void storm(LovyanGFX& g, int x, int y, int s, uint16_t color);
void fog(LovyanGFX& g, int x, int y, int s, uint16_t color);
void weather(LovyanGFX& g, int code, int x, int y, int s, uint16_t color);
void battery(LovyanGFX& g, int x, int y, int w, int h, int percent, uint16_t color);
void bell(LovyanGFX& g, int x, int y, int s, uint16_t color);
void play(LovyanGFX& g, int x, int y, int s, uint16_t color, bool playing);
void calendar(LovyanGFX& g, int x, int y, int s, uint16_t color);
void steps(LovyanGFX& g, int x, int y, int s, uint16_t color);
void chart(LovyanGFX& g, int x, int y, int s, uint16_t color);
void bluetooth(LovyanGFX& g, int x, int y, int s, uint16_t color);
void wifi(LovyanGFX& g, int x, int y, int s, uint16_t color);
void arrow(LovyanGFX& g, int x, int y, int s, uint16_t color, bool up);
void photo(LovyanGFX& g, int x, int y, int s, uint16_t color);
void quote(LovyanGFX& g, int x, int y, int s, uint16_t color);
}  // namespace icons
