#include "ui.h"

#include <LittleFS.h>
#include <esp_system.h>
#include <stdarg.h>

#include "app_state.h"
#include "clock.h"
#include "display.h"
#include "fredoka_font.h"
#include "icons.h"
#include "net.h"
#include "power.h"
#include "theme.h"
#include "version.h"

namespace {

struct Rect {
  int x, y, w, h;
};

// Everything is drawn into an off-screen strip ("band") and pushed to the
// panel in one transfer, so the user never sees a half-painted frame. A full
// 240x240 frame buffer (115 KB) would not leave enough heap for BLE + Wi-Fi
// + TLS on the ESP32-C3, so the screen is rendered as three horizontal bands
// that line up with the gaps between card rows.
constexpr int kBandMaxH = 116;
LGFX_Sprite s_band(&tft);
bool s_bandOk = false;
LovyanGFX* cv = &tft;  // current draw target
int s_oy = 0;          // y of the band currently being drawn

inline int Y(int y) { return y - s_oy; }

// Full-screen "stocks only" mode (settings flag 0x20) and dark theme (0x10).
inline bool stocksMode() { return (gState.settings.flags & 0x20) != 0; }
// The panel shows red and blue swapped relative to true colour (the diagnostics
// swatches prove it) while the UI palette is tuned for it, so photos always need
// their red/blue exchanged to look right.
inline bool photoSwapRB() { return true; }
inline bool darkTheme() { return (gState.settings.flags & 0x10) != 0; }
int s_stockPage = 0;

const char* weatherLabel(uint8_t code) {
  if (code == 0) return "Clear sky";
  if (code == 1) return "Mostly clear";
  if (code == 2) return "Partly cloudy";
  if (code == 3) return "Overcast";
  if (code == 45 || code == 48) return "Foggy";
  if (code >= 51 && code <= 57) return "Drizzle";
  if (code >= 61 && code <= 67) return "Rain";
  if (code >= 71 && code <= 77) return "Snow";
  if (code >= 80 && code <= 82) return "Showers";
  if (code == 85 || code == 86) return "Snow showers";
  if (code >= 95) return "Thunderstorm";
  return "Weather";
}

void sanitize(const char* src, char* out, size_t cap) {
  if (!out || cap == 0) return;
  size_t j = 0;
  for (size_t i = 0; src && src[i] && j + 1 < cap; ++i) {
    const unsigned char c = static_cast<unsigned char>(src[i]);
    if (c == '\n' || c == '\r') {
      out[j++] = ' ';
    } else if (c >= 32 && c < 127) {
      out[j++] = static_cast<char>(c);
    } else if (c >= 192) {
      out[j++] = '?';
    }
  }
  out[j] = 0;
}

void ellipsized(const char* text, int x, int y, int maxW, uint16_t color, uint16_t bg = C_CARD) {
  if (maxW <= 0) return;
  char clean[96];
  sanitize(text, clean, sizeof(clean));
  cv->setTextColor(color, bg);
  if (cv->textWidth(clean) <= maxW) {
    cv->drawString(clean, x, y);
    return;
  }
  char out[96];
  size_t len = strlen(clean);
  if (len >= sizeof(out)) len = sizeof(out) - 1;
  memcpy(out, clean, len);
  out[len] = 0;

  while (len > 0) {
    if (len > 2) {
      out[len - 1] = '.';
      out[len - 2] = '.';
      out[len] = 0;
    } else {
      out[len] = 0;
    }
    if (cv->textWidth(out) <= maxW) break;
    len--;
  }
  if (out[0]) {
    cv->drawString(out, x, y);
  }
}

void drawCenterTruncated(const char* text, int cx, int y, int maxW, uint16_t color, uint16_t bg = C_CARD) {
  if (maxW <= 0) return;
  char clean[96];
  sanitize(text, clean, sizeof(clean));
  cv->setTextColor(color, bg);
  if (cv->textWidth(clean) <= maxW) {
    cv->drawCenterString(clean, cx, y);
    return;
  }
  char out[96];
  size_t len = strlen(clean);
  if (len >= sizeof(out)) len = sizeof(out) - 1;
  memcpy(out, clean, len);
  out[len] = 0;

  while (len > 0) {
    if (len > 2) {
      out[len - 1] = '.';
      out[len - 2] = '.';
      out[len] = 0;
    } else {
      out[len] = 0;
    }
    if (cv->textWidth(out) <= maxW) break;
    len--;
  }
  if (out[0]) {
    cv->drawCenterString(out, cx, y);
  }
}

void wrapped(const char* text, int x, int y, int maxW, int lineH, int maxLines, uint16_t color, uint16_t bg = C_CARD) {
  if (maxW <= 0 || maxLines <= 0) return;
  char clean[120];
  sanitize(text, clean, sizeof(clean));
  cv->setTextColor(color, bg);
  char line[120] = {0};
  char word[32] = {0};
  int lineNo = 0;
  int wx = 0;
  for (size_t i = 0; i <= strlen(clean); ++i) {
    const char c = clean[i];
    if (c == ' ' || c == 0) {
      word[wx] = 0;
      if (wx > 0) {
        char candidate[120];
        if (line[0]) snprintf(candidate, sizeof(candidate), "%s %s", line, word);
        else snprintf(candidate, sizeof(candidate), "%s", word);
        if (cv->textWidth(candidate) <= maxW) {
          strlcpy(line, candidate, sizeof(line));
        } else {
          if (lineNo + 1 >= maxLines) {
            ellipsized(candidate, x, y + lineNo * lineH, maxW, color, bg);
            return;
          }
          ellipsized(line, x, y + lineNo * lineH, maxW, color, bg);
          lineNo++;
          strlcpy(line, word, sizeof(line));
        }
        wx = 0;
      }
    } else if (wx + 1 < sizeof(word)) {
      word[wx++] = c;
    }
  }
  if (line[0] && lineNo < maxLines) {
    ellipsized(line, x, y + lineNo * lineH, maxW, color, bg);
  }
}

uint16_t wash(uint16_t accent, uint8_t amount = 18) {
  return mixColor(accent, C_CARD, amount);
}

void card(const Rect& r, uint16_t accent, bool hero) {
  cv->fillRoundRect(r.x, r.y + 2, r.w, r.h, 16, mixColor(C_SHADOW, C_BG, 42));
  cv->fillRoundRect(r.x, r.y, r.w, r.h, 16, C_CARD);
  cv->drawRoundRect(r.x, r.y, r.w, r.h, 16, C_LINE);
  (void)accent;
  (void)hero;
}

void spark(const Rect& area, const int16_t* values, uint8_t count, uint16_t accent) {
  if (!values || count < 2 || area.w < 8 || area.h < 8) return;
  int lo = values[0];
  int hi = values[0];
  for (uint8_t i = 1; i < count; ++i) {
    lo = min(lo, static_cast<int>(values[i]));
    hi = max(hi, static_cast<int>(values[i]));
  }
  if (hi - lo < 6) {
    const int mid = area.y + area.h / 2;
    cv->drawFastHLine(area.x, mid, area.w, mixColor(accent, C_CARD, 55));
    return;
  }
  const int span = count - 1;
  const int pad = 2;
  const int usable = area.h - pad * 2;
  const int bottom = area.y + area.h - 1;
  int previousX = area.x;
  int previousY = area.y + pad + (hi - values[0]) * usable / (hi - lo);

  for (int x = 0; x < area.w; ++x) {
    int pos = x * span * 256 / max(1, area.w - 1);
    int index = pos >> 8;
    int frac = pos & 0xFF;
    if (index >= span) {
      index = span - 1;
      frac = 255;
    }
    const int value = values[index] +
                      ((static_cast<int>(values[index + 1]) - values[index]) * frac) / 256;
    const int y = area.y + pad + (hi - value) * usable / (hi - lo);
    if (y < bottom) cv->drawFastVLine(area.x + x, y, bottom - y, wash(accent, 16));
    if (x > 0) cv->drawLine(previousX, previousY, area.x + x, y, accent);
    previousX = area.x + x;
    previousY = y;
  }
  cv->drawFastHLine(area.x, bottom, area.w, mixColor(accent, C_CARD, 35));
  cv->fillCircle(previousX, previousY, 2, accent);
}
void drawClock(const Rect& r, uint16_t accent, bool hero) {
  char time[20];
  char date[32];
  const bool twelve = (gState.settings.flags & 0x01) != 0;
  const bool seconds = (gState.settings.flags & 0x08) != 0;
  espclock::formatTime(time, sizeof(time), twelve, seconds);
  espclock::formatDate(date, sizeof(date));
  if (hero) {
    cv->setFont(&Fredoka44);
    if (cv->textWidth(time) > r.w - 24) cv->setFont(&Fredoka28);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawCenterString(time, r.x + r.w / 2, r.y + (seconds ? 12 : 16));
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(date, r.x + r.w / 2, r.y + (seconds ? 62 : 68), r.w - 24, C_MUTED);
    if (seconds) {
      const int sec = espclock::valid() ? (espclock::epochLocal() % 60) : 0;
      cv->fillRoundRect(r.x + 24, r.y + r.h - 16, r.w - 48, 4, 2, C_LINE);
      if (sec > 0) cv->fillRoundRect(r.x + 24, r.y + r.h - 16, (r.w - 48) * sec / 60, 4, 2, accent);
    }
  } else {
    cv->setFont(&Fredoka28);
    if (cv->textWidth(time) > r.w - 16) cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawCenterString(time, r.x + r.w / 2, r.y + 20);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(date, r.x + r.w / 2, r.y + 58, r.w - 16, C_MUTED);
  }
}

void drawWeather(const Rect& r, uint16_t accent, bool hero) {
  if (!gState.weatherValid) {
    icons::weather(*cv, 1, r.x + r.w / 2 - (hero ? 18 : 12), r.y + (hero ? 18 : 12), hero ? 36 : 24, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "No weather data" : "No weather", r.x + r.w / 2, r.y + (hero ? 62 : 44), r.w - 16, C_MUTED);
    return;
  }
  const auto& w = gState.weather;
  char temp[12];
  snprintf(temp, sizeof(temp), "%d", w.temp_c10 / 10);
  char hi[24];
  snprintf(hi, sizeof(hi), "H %d  L %d", w.hi_c10 / 10, w.lo_c10 / 10);
  if (hero) {
    icons::weather(*cv, w.code, r.x + 16, r.y + 15, 34, accent);
    cv->setFont(&Fredoka44);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(temp, r.x + 56, r.y + 2);
    const int tempW = cv->textWidth(temp);
    cv->fillCircle(r.x + 56 + tempW + 5, r.y + 12, 3, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString("C", r.x + 56 + tempW + 12, r.y + 10);
    ellipsized(weatherLabel(w.code), r.x + 16, r.y + 49, r.w - 110, C_MUTED);
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(w.city[0] ? w.city : "Local", r.x + 16, r.y + 64, r.w - 110, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString(hi, r.x + 16, r.y + 81);
    if (w.trend_count >= 2) {
      spark(Rect{r.x + r.w - 90, r.y + 45, 74, 36}, w.trend_c10, w.trend_count, accent);
    }
  } else {
    icons::weather(*cv, w.code, r.x + 10, r.y + 10, 22, accent);
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(temp, r.x + 38, r.y + 7);
    cv->fillCircle(r.x + 38 + cv->textWidth(temp) + 4, r.y + 11, 2, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(w.city[0] ? w.city : "Local", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    if (w.trend_count >= 2) {
      spark(Rect{r.x + 10, r.y + 54, r.w - 20, 24}, w.trend_c10, w.trend_count, accent);
    } else {
      ellipsized(weatherLabel(w.code), r.x + 10, r.y + 54, r.w - 20, C_MUTED);
      ellipsized(hi, r.x + 10, r.y + 68, r.w - 20, C_MUTED);
    }
  }
}

void drawPhoneBattery(const Rect& r, uint16_t accent, bool hero) {
  const bool known = gState.phoneBatteryValid;
  const int level = known ? constrain(static_cast<int>(gState.phoneBattery.percent), 0, 100) : 0;
  const bool charging = known && gState.phoneBattery.charging;
  char pct[8];
  if (known) snprintf(pct, sizeof(pct), "%d%%", level);
  else strlcpy(pct, "--", sizeof(pct));
  const char* label = !known ? "Waiting for phone" : charging ? "Phone charging" : "Phone";
  const uint16_t color = charging ? C_GOOD : (known && level <= 20) ? C_DANGER : accent;
  // While the phone charges, the battery fills up from its real level to full
  // in steps, then starts over.
  const int shown = charging ? level + (100 - level) * static_cast<int>((millis() / 450) % 6) / 5 : level;
  if (hero) {
    cv->setFont(&Fredoka44);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(pct, r.x + 18, r.y + 7);
    icons::battery(*cv, r.x + 132, r.y + 14, 62, 30, shown, color);
    cv->fillRoundRect(r.x + 18, r.y + 58, r.w - 36, 7, 3, C_LINE);
    if (shown > 0) cv->fillRoundRect(r.x + 18, r.y + 58, (r.w - 36) * shown / 100, 7, 3, color);
    cv->setFont(&Fredoka16);
    ellipsized(label, r.x + 18, r.y + 70, r.w - 36, charging ? C_GOOD : C_MUTED);
  } else {
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(pct, r.x + 10, r.y + 10);
    icons::battery(*cv, r.x + r.w - 38, r.y + 12, 28, 14, shown, color);
    cv->fillRoundRect(r.x + 10, r.y + 36, r.w - 20, 4, 2, C_LINE);
    if (shown > 0) cv->fillRoundRect(r.x + 10, r.y + 36, (r.w - 20) * shown / 100, 4, 2, color);
    cv->setFont(&Fredoka12);
    ellipsized(label, r.x + 10, r.y + 48, r.w - 20, charging ? C_GOOD : C_MUTED);
  }
}

void drawBattery(const Rect& r, uint16_t accent, bool hero) {
  // No valid cell reading (e.g. running from USB with the cell disconnected).
  const bool known = gState.battery.millivolts > 0 || gState.chargeFull;
  char pct[8];
  if (known) snprintf(pct, sizeof(pct), "%d%%", gState.battery.percent);
  else strlcpy(pct, "--", sizeof(pct));
  char volts[20];
  if (known) snprintf(volts, sizeof(volts), "%.2f V", gState.battery.millivolts / 1000.0f);
  else strlcpy(volts, gState.usbPower ? "No cell reading" : "Measuring...", sizeof(volts));
  const int level = constrain(static_cast<int>(gState.battery.percent), 0, 100);
  const uint16_t statusColor = gState.charging || gState.chargeFull ? C_GOOD
                                 : gState.usbPower ? C_SKY : C_MUTED;
  const char* statusStr = gState.chargeFull ? "Full"
                          : (gState.charging && known) ? "Charging"
                          : gState.usbPower ? "USB power" : "On battery";
  if (hero) {
    cv->setFont(&Fredoka44);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(pct, r.x + 18, r.y + 7);
    icons::battery(*cv, r.x + 132, r.y + 14, 62, 30, level, accent);
    cv->fillRoundRect(r.x + 18, r.y + 58, r.w - 36, 7, 3, C_LINE);
    if (level > 0) cv->fillRoundRect(r.x + 18, r.y + 58, (r.w - 36) * level / 100, 7, 3, accent);
    cv->setFont(&Fredoka16);
    cv->setTextColor(statusColor, C_CARD);
    ellipsized(statusStr, r.x + 18, r.y + 70, r.w - 96, statusColor);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString(volts, r.x + r.w - 18 - cv->textWidth(volts), r.y + 72);
  } else {
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(pct, r.x + 10, r.y + 10);
    icons::battery(*cv, r.x + r.w - 38, r.y + 12, 28, 14, level, accent);
    cv->fillRoundRect(r.x + 10, r.y + 36, r.w - 20, 4, 2, C_LINE);
    if (level > 0) cv->fillRoundRect(r.x + 10, r.y + 36, (r.w - 20) * level / 100, 4, 2, accent);
    cv->setFont(&Fredoka12);
    cv->setTextColor(statusColor, C_CARD);
    ellipsized(statusStr, r.x + 10, r.y + 48, r.w - 20, statusColor);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(volts, r.x + 10, r.y + 66, r.w - 20, C_MUTED);
  }
}

void formatPrice(const espw::StockPayload& s, char* out, size_t cap) {
  const float price = s.price_cents / 100.0f;
  const char* fmt = price >= 10000.0f ? "%.0f" : "%.2f";
  char num[16];
  snprintf(num, sizeof(num), fmt, price);
  if (s.currency == 0) snprintf(out, cap, "Rs %s", num);
  else if (s.currency == 1) snprintf(out, cap, "$%s", num);
  else snprintf(out, cap, "%s", num);
}

// option >= 6: show the whole watchlist as a compact list.
void drawWatchlist(const Rect& r, uint16_t accent, bool hero) {
  int shown = 0;
  const int maxRows = hero ? 4 : 3;
  const int rowH = hero ? 21 : 27;
  const int top = r.y + (hero ? 10 : 9);
  for (int i = 0; i < 6 && shown < maxRows; ++i) {
    if (!gState.stockValid[i]) continue;
    const auto& s = gState.stocks[i];
    char sym[13], price[20], change[12];
    sanitize(s.symbol, sym, sizeof(sym));
    formatPrice(s, price, sizeof(price));
    snprintf(change, sizeof(change), "%+.1f%%", s.change_bp / 100.0f);
    const uint16_t trend = s.change_bp >= 0 ? C_GOOD : C_DANGER;
    const int y = top + shown * rowH;
    if (shown > 0) cv->drawFastHLine(r.x + 14, y - 3, r.w - 28, C_LINE);
    if (hero) {
      cv->setFont(&Fredoka16);
      ellipsized(sym, r.x + 16, y, 74, C_INK);
      cv->setFont(&Fredoka12);
      cv->setTextColor(C_MUTED, C_CARD);
      cv->drawRightString(price, r.x + r.w - 62, y + 3);
      cv->setTextColor(trend, C_CARD);
      cv->drawRightString(change, r.x + r.w - 14, y + 3);
    } else {
      cv->setFont(&Fredoka12);
      ellipsized(sym, r.x + 12, y, r.w - 60, C_INK);
      cv->setTextColor(trend, C_CARD);
      cv->drawRightString(change, r.x + r.w - 10, y);
      ellipsized(price, r.x + 12, y + 12, r.w - 22, C_MUTED);
    }
    ++shown;
  }
  if (shown == 0) {
    icons::chart(*cv, r.x + r.w / 2 - 12, r.y + (hero ? 18 : 12), 24, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    drawCenterTruncated("Add stocks in app", r.x + r.w / 2, r.y + (hero ? 52 : 46), r.w - 16, C_MUTED);
  }
}

void drawStocks(const Rect& r, uint16_t accent, bool hero, uint8_t option) {
  if (option >= 6) {
    drawWatchlist(r, accent, hero);
    return;
  }
  const uint8_t idx = option % 6;
  if (!gState.stockValid[idx]) {
    icons::chart(*cv, r.x + r.w / 2 - 12, r.y + (hero ? 18 : 12), 24, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "Add stocks in app" : "Add stocks", r.x + r.w / 2, r.y + (hero ? 52 : 44), r.w - 16, C_MUTED);
    if (!hero) {
      drawCenterTruncated("in the app", r.x + r.w / 2, r.y + 60, r.w - 16, C_MUTED);
    }
    return;
  }
  const auto& s = gState.stocks[idx];
  char sym[13];
  sanitize(s.symbol, sym, sizeof(sym));
  char priceStr[20];
  formatPrice(s, priceStr, sizeof(priceStr));
  char change[16];
  snprintf(change, sizeof(change), "%+.2f%%", s.change_bp / 100.0f);
  const uint16_t trend = s.change_bp >= 0 ? C_GOOD : C_DANGER;
  if (hero) {
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(sym, r.x + 18, r.y + 9, 80, C_MUTED);
    cv->setFont(&Fredoka28);
    cv->setTextColor(C_INK, C_CARD);
    if (cv->textWidth(priceStr) > 86) cv->setFont(&Fredoka20);
    ellipsized(priceStr, r.x + 18, r.y + 22, 86, C_INK);
    cv->fillRoundRect(r.x + 18, r.y + 56, 58, 17, 8, trend);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_CARD, trend);
    cv->drawCenterString(change, r.x + 47, r.y + 59);
    icons::arrow(*cv, r.x + 82, r.y + 60, 12, trend, s.change_bp >= 0);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString(s.market == 1 ? "Open  24D" : "Closed  24D", r.x + 18, r.y + 80);
    spark(Rect{r.x + 108, r.y + 35, r.w - 126, 52}, s.spark, 24, trend);
  } else {
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(sym, r.x + 10, r.y + 8, r.w - 20, C_MUTED);
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(priceStr, r.x + 10, r.y + 22, r.w - 20, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(trend, C_CARD);
    ellipsized(change, r.x + 10, r.y + 44, r.w - 20, trend);
    spark(Rect{r.x + 10, r.y + 60, r.w - 20, 19}, s.spark, 24, trend);
  }
}

void drawNotifications(const Rect& r, uint16_t accent, bool hero) {
  if (!gState.notificationValid) {
    icons::bell(*cv, r.x + r.w / 2 - 11, r.y + (hero ? 20 : 14), 22, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated("All quiet", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& n = gState.notification;
  char app[17], title[41], body[57];
  sanitize(n.app, app, sizeof(app));
  sanitize(n.title, title, sizeof(title));
  sanitize(n.body, body, sizeof(body));
  if (hero) {
    icons::bell(*cv, r.x + 16, r.y + 12, 28, accent);
    cv->setFont(&Fredoka28);
    cv->setTextColor(C_INK, C_CARD);
    char count[8];
    snprintf(count, sizeof(count), "%d", n.count);
    cv->drawString(count, r.x + 50, r.y + 10);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString("new", r.x + 76, r.y + 22);
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(app[0] ? app : "Notification", r.x + 20, r.y + 48, r.w - 40, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    wrapped(title[0] ? title : body, r.x + 20, r.y + 68, r.w - 40, 14, 2, C_INK);
  } else {
    icons::bell(*cv, r.x + 10, r.y + 10, 20, accent);
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    char count[8];
    snprintf(count, sizeof(count), "%d", n.count);
    cv->drawString(count, r.x + 36, r.y + 8);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(app[0] ? app : "Notice", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    ellipsized(title[0] ? title : body, r.x + 10, r.y + 52, r.w - 20, C_INK);
    ellipsized(body, r.x + 10, r.y + 68, r.w - 20, C_MUTED);
  }
}

void drawMedia(const Rect& r, uint16_t accent, bool hero) {
  if (!gState.mediaValid) {
    icons::play(*cv, r.x + r.w / 2 - 12, r.y + (hero ? 20 : 14), 24, mixColor(accent, C_CARD, 45), false);
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "Nothing playing" : "No media", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& m = gState.media;
  char title[41], artist[25];
  sanitize(m.title, title, sizeof(title));
  sanitize(m.artist, artist, sizeof(artist));
  if (hero) {
    cv->fillCircle(r.x + 40, r.y + 38, 22, accent);
    icons::play(*cv, r.x + 29, r.y + 26, 24, C_INK, m.playing != 0);
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Unknown track", r.x + 72, r.y + 14, r.w - 86, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(artist[0] ? artist : "Unknown artist", r.x + 72, r.y + 36, r.w - 86, C_MUTED);
    cv->fillRoundRect(r.x + 72, r.y + 58, r.w - 86, 5, 2, C_LINE);
    cv->fillRoundRect(r.x + 72, r.y + 58, (r.w - 86) * constrain(m.progress_pct, 0, 100) / 100, 5, 2, accent);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(m.playing ? "NOW PLAYING" : "PAUSED", r.x + 20, r.y + r.h - 18, r.w - 40, C_MUTED);
  } else {
    icons::play(*cv, r.x + 10, r.y + 10, 18, accent, m.playing != 0);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Unknown track", r.x + 34, r.y + 12, r.w - 44, C_INK);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(artist[0] ? artist : "Unknown artist", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    cv->fillRoundRect(r.x + 10, r.y + 58, r.w - 20, 4, 2, C_LINE);
    cv->fillRoundRect(r.x + 10, r.y + 58, (r.w - 20) * constrain(m.progress_pct, 0, 100) / 100, 4, 2, accent);
    ellipsized(m.playing ? "Playing" : "Paused", r.x + 10, r.y + 68, r.w - 20, C_MUTED);
  }
}

void drawCalendar(const Rect& r, uint16_t accent, bool hero) {
  if (!gState.calendarValid) {
    icons::calendar(*cv, r.x + r.w / 2 - 12, r.y + (hero ? 20 : 14), 24, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "No upcoming events" : "No events", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& c = gState.calendar;
  char title[41], loc[17], day[12], time[8];
  sanitize(c.title, title, sizeof(title));
  sanitize(c.location, loc, sizeof(loc));
  espclock::formatEpochDay(c.start, day, sizeof(day));
  espclock::formatEpochTime(c.start, time, sizeof(time));
  if (hero) {
    cv->fillRoundRect(r.x + 14, r.y + 14, 52, 54, 10, accent);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_ONACCENT, accent);
    String d = String(day);
    int spaceIdx = d.indexOf(' ');
    if (spaceIdx >= 0) {
      cv->drawCenterString(d.substring(0, spaceIdx).c_str(), r.x + 40, r.y + 20);
      cv->setFont(&Fredoka20);
      cv->drawCenterString(d.substring(spaceIdx + 1).c_str(), r.x + 40, r.y + 38);
    } else {
      cv->drawCenterString(day, r.x + 40, r.y + 24);
    }
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_INK, C_CARD);
    wrapped(title[0] ? title : "Untitled event", r.x + 76, r.y + 14, r.w - 90, 18, 2, C_INK);
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString(c.all_day ? "All day" : time, r.x + 76, r.y + 56);
    ellipsized(loc, r.x + 76, r.y + 74, r.w - 90, C_MUTED);
  } else {
    cv->fillRoundRect(r.x + 8, r.y + 10, 36, 36, 8, accent);
    cv->setTextColor(C_ONACCENT, accent);
    String d = String(day);
    int sp = d.indexOf(' ');
    if (sp >= 0) {
      cv->setFont(&Fredoka16);
      cv->drawCenterString(d.substring(0, sp).c_str(), r.x + 26, r.y + 13);
      cv->setFont(&Fredoka12);
      cv->drawCenterString(d.substring(sp + 1).c_str(), r.x + 26, r.y + 28);
    } else {
      cv->setFont(&Fredoka12);
      cv->drawCenterString(day, r.x + 26, r.y + 18);
    }
    cv->setFont(&Fredoka12);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Event", r.x + 48, r.y + 12, r.w - 56, C_INK);
    cv->setTextColor(C_MUTED, C_CARD);
    ellipsized(c.all_day ? "All day" : time, r.x + 48, r.y + 30, r.w - 56, C_MUTED);
    ellipsized(loc, r.x + 10, r.y + 54, r.w - 20, C_MUTED);
  }
}

void drawSteps(const Rect& r, uint16_t accent, bool hero) {
  if (!gState.stepsValid) {
    icons::steps(*cv, r.x + r.w / 2 - 12, r.y + (hero ? 20 : 14), 24, mixColor(accent, C_CARD, 45));
    cv->setFont(hero ? &Fredoka16 : &Fredoka12);
    drawCenterTruncated("No step data", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& st = gState.steps;
  const uint32_t goal = st.goal ? st.goal : 8000;
  const int pct = constrain(static_cast<int>(st.steps * 100UL / goal), 0, 100);
  char count[16], sub[24];
  snprintf(count, sizeof(count), "%lu", static_cast<unsigned long>(st.steps));
  snprintf(sub, sizeof(sub), "of %lu", static_cast<unsigned long>(goal));
  if (hero) {
    icons::steps(*cv, r.x + 18, r.y + 18, 30, accent);
    cv->setFont(&Fredoka44);
    cv->setTextColor(C_INK, C_CARD);
    cv->drawString(count, r.x + 60, r.y + 6);
    cv->setFont(&Fredoka16);
    cv->setTextColor(C_MUTED, C_CARD);
    cv->drawString(sub, r.x + 60, r.y + 52);
    cv->fillRoundRect(r.x + 18, r.y + 78, r.w - 36, 7, 3, C_LINE);
    if (pct > 0) cv->fillRoundRect(r.x + 18, r.y + 78, (r.w - 36) * pct / 100, 7, 3, accent);
  } else {
    icons::steps(*cv, r.x + 10, r.y + 10, 20, accent);
    cv->setFont(&Fredoka20);
    cv->setTextColor(C_INK, C_CARD);
    ellipsized(count, r.x + 10, r.y + 34, r.w - 20, C_INK);
    cv->setFont(&Fredoka12);
    ellipsized(sub, r.x + 10, r.y + 58, r.w - 20, C_MUTED);
    cv->fillRoundRect(r.x + 10, r.y + 76, r.w - 20, 4, 2, C_LINE);
    if (pct > 0) cv->fillRoundRect(r.x + 10, r.y + 76, (r.w - 20) * pct / 100, 4, 2, accent);
  }
}

void drawText(const Rect& r, uint16_t accent, bool hero, uint8_t slot) {
  const uint8_t idx = slot % 4;
  char text[97];
  sanitize(gState.texts[idx], text, sizeof(text));
  if (hero) {
    icons::quote(*cv, r.x + 14, r.y + 8, 22, accent);
    cv->setFont(&Fredoka16);
    wrapped(text[0] ? text : "Add a note in the app", r.x + 18, r.y + 32, r.w - 36, 18, 3, C_INK);
  } else {
    icons::quote(*cv, r.x + 10, r.y + 8, 18, accent);
    cv->setFont(&Fredoka12);
    wrapped(text[0] ? text : "Add note in app", r.x + 10, r.y + 30, r.w - 20, 14, 3, C_INK);
  }
}

// Paints the four corner wedges outside a rounded rectangle with the page
// background, so a square image ends up with the same rounded shape as a card.
void trimCorners(const Rect& r, int radius, uint16_t bg) {
  for (int dy = 0; dy < radius; ++dy) {
    const float yy = radius - dy - 0.5f;
    const int cut = radius - static_cast<int>(sqrtf(static_cast<float>(radius * radius) - yy * yy));
    if (cut <= 0) continue;
    cv->fillRect(r.x, r.y + dy, cut, 1, bg);
    cv->fillRect(r.x + r.w - cut, r.y + dy, cut, 1, bg);
    cv->fillRect(r.x, r.y + r.h - 1 - dy, cut, 1, bg);
    cv->fillRect(r.x + r.w - cut, r.y + r.h - 1 - dy, cut, 1, bg);
  }
}

void drawPhoto(const Rect& r, uint16_t accent, bool hero) {
  if (gState.photoReady) {
    // The photo is 240x240: scale it to cover the card, centred, then round the corners.
    const float scale = max(r.w / 240.0f, r.h / 240.0f);
    cv->clearClipRect();
    cv->setClipRect(r.x, r.y, r.w, r.h);
    cv->drawJpgFile(LittleFS, "/photo.jpg", r.x, r.y, r.w, r.h, 0, 0, scale, scale,
                    lgfx::datum_t::middle_center);
    if (photoSwapRB()) {
      // Panel/JPEG channel order mismatch: swap red and blue in the drawn area.
      const int y0 = max(r.y, 0), y1 = min(r.y + r.h, static_cast<int>(cv->height()));
      const int x0 = max(r.x, 0), x1 = min(r.x + r.w, static_cast<int>(cv->width()));
      for (int yy = y0; yy < y1; ++yy) {
        for (int xx = x0; xx < x1; ++xx) {
          const auto c = cv->readPixelRGB(xx, yy);
          cv->writePixel(xx, yy, cv->color888(c.B8(), c.G8(), c.R8()));
        }
      }
    }
    trimCorners(r, 16, C_BG);
    cv->drawRoundRect(r.x, r.y, r.w, r.h, 16, C_LINE);
    return;
  }
  icons::photo(*cv, r.x + r.w / 2 - (hero ? 20 : 14), r.y + (hero ? 18 : 12), hero ? 40 : 28, accent);
  cv->setFont(hero ? &Fredoka16 : &Fredoka12);
  cv->setTextColor(C_MUTED, C_CARD);
  drawCenterTruncated("Send a photo", r.x + r.w / 2, r.y + r.h - (hero ? 30 : 24), r.w - 16, C_MUTED);
}

void drawWidget(const espw::SlotConfig& slot, const Rect& r, bool hero) {
  const uint16_t accent = accentColor(slot.accent);
  card(r, accent, hero);
  cv->setClipRect(r.x + 2, r.y + 2, r.w - 4, r.h - 4);
  switch (slot.type) {
    case espw::WidgetClock: drawClock(r, accent, hero); break;
    case espw::WidgetWeather: drawWeather(r, accent, hero); break;
    case espw::WidgetBattery: drawBattery(r, accent, hero); break;
    case espw::WidgetStocks: drawStocks(r, accent, hero, slot.option); break;
    case espw::WidgetNotifications: drawNotifications(r, accent, hero); break;
    case espw::WidgetMedia: drawMedia(r, accent, hero); break;
    case espw::WidgetCalendar: drawCalendar(r, accent, hero); break;
    case espw::WidgetSteps: drawSteps(r, accent, hero); break;
    case espw::WidgetText: drawText(r, accent, hero, slot.option); break;
    case espw::WidgetPhoto: drawPhoto(r, accent, hero); break;
    case espw::WidgetPhoneBattery: drawPhoneBattery(r, accent, hero); break;
    default: {
      cv->setFont(&Fredoka12);
      cv->setTextColor(C_MUTED, C_CARD);
      drawCenterTruncated("Empty", r.x + r.w / 2, r.y + r.h / 2 - 6, r.w - 16, C_MUTED);
      break;
    }
  }
  cv->clearClipRect();
}
// ---------------------------------------------------------------- header --
// Blink phase for "working on it" states (advertising, pairing, Wi-Fi joining).
bool blinkOn() { return ((millis() / 500) & 1) == 0; }

uint16_t bleIconColor() {
  if (gState.bleConnected && !gState.pairingActive) return C_RAIN;   // solid: linked
  if (gState.pairingActive || gState.bleActive) return blinkOn() ? C_INK : C_LINE;  // blinking: searching / pairing
  return C_LINE;                                                      // off
}

uint16_t wifiIconColor() {
  switch (gState.wifiState) {
    case 2: return C_GOOD;                         // connected: solid
    case 1: return blinkOn() ? C_INK : C_LINE;     // joining: blink
    case 3: return C_DANGER;                       // failed
    default: return C_LINE;                        // off
  }
}

void header() {
  const int top = Y(0);
  const int pct = constrain(static_cast<int>(gState.battery.percent), 0, 100);
  const uint16_t level = gState.charging || gState.chargeFull
                             ? C_GOOD
                             : pct > 50 ? C_GOOD : pct > 20 ? C_SUN : C_DANGER;

  // Right cluster, laid out right-to-left from the margin.
  const bool known = gState.battery.millivolts > 0 || gState.chargeFull;
  char battery[8];
  if (known) snprintf(battery, sizeof(battery), "%d%%", pct);
  else strlcpy(battery, gState.usbPower ? "USB" : "--", sizeof(battery));
  cv->setFont(&Fredoka12);
  const int pctW = cv->textWidth(battery);
  int x = SCREEN_W - UI_MARGIN - pctW;
  cv->setTextColor(C_INK, C_BG);
  cv->drawString(battery, x, top + 6);

  x -= 4 + 2;                      // gap + nub
  const int bw = 22, bh = 12, by = top + 6;
  cv->fillRect(x, by + 4, 2, 4, C_MUTED);
  x -= bw;
  cv->drawRoundRect(x, by, bw, bh, 3, C_MUTED);
  const int inner = (bw - 4) * pct / 100;
  if (inner > 0) cv->fillRoundRect(x + 2, by + 2, inner, bh - 4, 2, level);
  if (gState.charging || gState.usbPower) {
    // little bolt so charging is obvious even at 100% fill
    const int cx = x + bw / 2;
    cv->fillTriangle(cx + 1, by + 1, cx - 3, by + 7, cx, by + 7, C_INK);
    cv->fillTriangle(cx - 1, by + bh - 1, cx + 3, by + 5, cx, by + 5, C_INK);
  }

  x -= 8 + 14;
  icons::wifi(*cv, x, top + 5, 14, wifiIconColor());
  x -= 4 + 14;
  icons::bluetooth(*cv, x, top + 5, 14, bleIconColor());

  // Left: today's date (or a hint until the phone has sent the time).
  char date[32];
  if (stocksMode()) {
    espclock::formatTime(date, sizeof(date), (gState.settings.flags & 0x01) != 0, false);
  } else {
    espclock::formatDate(date, sizeof(date));
  }
  if (!espclock::valid()) strlcpy(date, "Not synced", sizeof(date));
  cv->setFont(&Fredoka16);
  ellipsized(date, UI_MARGIN + 2, top + 3, x - UI_MARGIN - 8, C_INK, C_BG);
}

// Page indicator dots, centred in the bottom margin.
void pageDotsN(int count, int cur) {
  if (count < 2) return;
  const int y = Y(SCREEN_H - 5);
  const int step = 9;
  int x = SCREEN_W / 2 - ((count - 1) * step + 10) / 2;
  for (int i = 0; i < count; ++i) {
    if (i == cur) {
      cv->fillRoundRect(x, y - 2, 12, 4, 2, C_INK);
      x += 12 + 5;
    } else {
      cv->fillCircle(x + 2, y, 2, C_SHADOW);
      x += step;
    }
  }
}

void pageDots() { pageDotsN(statePageCount(), stateCurrentPage()); }

// ------------------------------------------------------------------ pages --
struct PageGeometry {
  Rect slots[4];
  bool hero[4];
  int count;
  int bandSplit[2];  // y where band 1 and band 2 start
};

PageGeometry geometry(uint8_t page) {
  PageGeometry g{};
  const auto& cfg = gState.layout.page[page];
  const int w = (SCREEN_W - UI_MARGIN * 2 - UI_GAP) / 2;
  if (cfg.template_id == espw::TemplateGrid) {
    const int h = (SCREEN_H - UI_CONTENT_Y - UI_BOTTOM - UI_GAP) / 2;
    for (int i = 0; i < 4; ++i) {
      g.slots[i] = Rect{UI_MARGIN + (i % 2) * (w + UI_GAP), UI_CONTENT_Y + (i / 2) * (h + UI_GAP), w, h};
      g.hero[i] = false;
    }
    g.count = 4;
    g.bandSplit[1] = UI_CONTENT_Y + h + 4;
  } else {
    const int smallY = UI_CONTENT_Y + UI_HERO_H + UI_GAP;
    const int smallH = SCREEN_H - UI_BOTTOM - smallY;
    g.slots[0] = Rect{UI_MARGIN, UI_CONTENT_Y, SCREEN_W - UI_MARGIN * 2, UI_HERO_H};
    g.slots[1] = Rect{UI_MARGIN, smallY, w, smallH};
    g.slots[2] = Rect{UI_MARGIN + w + UI_GAP, smallY, w, smallH};
    g.hero[0] = true;
    g.hero[1] = g.hero[2] = false;
    g.count = 3;
    g.bandSplit[1] = UI_CONTENT_Y + UI_HERO_H + 4;
  }
  g.bandSplit[0] = UI_CONTENT_Y - 2;
  return g;
}

void drawToast() {
  if (!gState.toast[0] || millis() > gState.toastUntil) return;
  char clean[49];
  sanitize(gState.toast, clean, sizeof(clean));
  cv->setFont(&Fredoka12);
  int w = static_cast<int>(cv->textWidth(clean)) + 32;
  if (w > 220) w = 220;
  const int x = (SCREEN_W - w) / 2;
  const int y = Y(SCREEN_H - UI_BOTTOM - 34);
  cv->fillRoundRect(x + 1, y + 2, w, 28, 14, C_SHADOW);
  cv->fillRoundRect(x, y, w, 28, 14, C_INK);
  drawCenterTruncated(clean, x + w / 2, y + 8, w - 20, C_CARD, C_INK);
}

void drawPairing() {
  if (!gState.pairingActive) return;
  const int y0 = Y(UI_CONTENT_Y);
  const int h = SCREEN_H - UI_CONTENT_Y - UI_BOTTOM;
  cv->fillRoundRect(UI_MARGIN + 1, y0 + 2, SCREEN_W - UI_MARGIN * 2, h, 18, C_SHADOW);
  cv->fillRoundRect(UI_MARGIN, y0, SCREEN_W - UI_MARGIN * 2, h, 18, C_CARD);
  cv->drawRoundRect(UI_MARGIN, y0, SCREEN_W - UI_MARGIN * 2, h, 18, C_LINE);
  const int cx = SCREEN_W / 2;
  icons::bluetooth(*cv, cx - 10, y0 + 14, 20, C_RAIN);
  cv->setFont(&Fredoka16);
  cv->setTextColor(C_INK, C_CARD);
  cv->drawCenterString("Pair with phone", cx, y0 + 40);
  char pin[8];
  snprintf(pin, sizeof(pin), "%06lu", static_cast<unsigned long>(gState.pairingPasskey));
  cv->setFont(&Fredoka44);
  cv->drawCenterString(pin, cx, y0 + 70);
  cv->setFont(&Fredoka12);
  cv->setTextColor(C_MUTED, C_CARD);
  cv->drawCenterString("Type this code on your phone", cx, y0 + 134);
  cv->drawCenterString("You only need to do this once", cx, y0 + 152);
}


// ---------------------------------------------------------- stocks mode --
int validStocks(uint8_t* idx) {
  int n = 0;
  for (uint8_t i = 0; i < 6; ++i) {
    if (gState.stockValid[i]) idx[n++] = i;
  }
  return n;
}

// One page with every stock as a row (only when there are two or more), then
// one full-screen page per stock.
int stockPageCount() {
  uint8_t idx[6];
  const int n = validStocks(idx);
  return n >= 2 ? n + 1 : 1;
}

void stockRow(const Rect& r, const espw::StockPayload& st) {
  const uint16_t trend = st.change_bp >= 0 ? C_GOOD : C_DANGER;
  cv->fillRoundRect(r.x, r.y, r.w, r.h, 10, C_CARD);
  cv->drawRoundRect(r.x, r.y, r.w, r.h, 10, C_LINE);
  char sym[13], price[20], change[16];
  sanitize(st.symbol, sym, sizeof(sym));
  formatPrice(st, price, sizeof(price));
  snprintf(change, sizeof(change), "%+.2f%%", st.change_bp / 100.0f);
  cv->setFont(&Fredoka16);
  if (cv->textWidth(sym) > 66) cv->setFont(&Fredoka12);  // long tickers such as RELIANCE
  ellipsized(sym, r.x + 10, r.y + r.h / 2 - 8, 68, C_INK);
  spark(Rect{r.x + 82, r.y + 6, 36, r.h - 12}, st.spark, 24, trend);
  cv->setFont(&Fredoka16);
  cv->setTextColor(C_INK, C_CARD);
  cv->drawRightString(price, r.x + r.w - 10, r.y + r.h / 2 - 15);
  cv->setFont(&Fredoka12);
  cv->setTextColor(trend, C_CARD);
  cv->drawRightString(change, r.x + r.w - 10, r.y + r.h / 2 + 2);
}

void stockDetail(const Rect& r, const espw::StockPayload& st) {
  const uint16_t trend = st.change_bp >= 0 ? C_GOOD : C_DANGER;
  card(r, trend, true);
  cv->setClipRect(r.x + 2, r.y + 2, r.w - 4, r.h - 4);
  char sym[13], price[20], change[16];
  sanitize(st.symbol, sym, sizeof(sym));
  formatPrice(st, price, sizeof(price));
  snprintf(change, sizeof(change), "%+.2f%%", st.change_bp / 100.0f);
  cv->setFont(&Fredoka20);
  ellipsized(sym, r.x + 16, r.y + 12, 120, C_INK);
  cv->setFont(&Fredoka12);
  cv->setTextColor(C_MUTED, C_CARD);
  cv->drawRightString(st.market == 1 ? "Market open" : "Market closed", r.x + r.w - 16, r.y + 16);
  cv->setFont(&Fredoka44);
  if (cv->textWidth(price) > r.w - 32) cv->setFont(&Fredoka28);
  cv->setTextColor(C_INK, C_CARD);
  cv->drawString(price, r.x + 16, r.y + 38);
  cv->fillRoundRect(r.x + 16, r.y + 92, 92, 24, 12, trend);
  cv->setFont(&Fredoka16);
  cv->setTextColor(C_WHITE, trend);
  cv->drawCenterString(change, r.x + 16 + 46, r.y + 95);
  icons::arrow(*cv, r.x + 120, r.y + 97, 14, trend, st.change_bp >= 0);
  spark(Rect{r.x + 16, r.y + 126, r.w - 32, 52}, st.spark, 24, trend);
  cv->setFont(&Fredoka12);
  cv->setTextColor(C_MUTED, C_CARD);
  cv->drawCenterString("last 24 days", r.x + r.w / 2, r.y + r.h - 18);
  cv->clearClipRect();
}

void drawStocksScreen(int bandY, int bandH) {
  uint8_t idx[6];
  const int n = validStocks(idx);
  const int pages = stockPageCount();
  if (s_stockPage >= pages) s_stockPage = 0;
  if (n == 0) {
    const Rect r{UI_MARGIN, UI_CONTENT_Y, SCREEN_W - UI_MARGIN * 2, SCREEN_H - UI_CONTENT_Y - UI_BOTTOM};
    if (r.y + r.h > bandY && r.y < bandY + bandH) {
      card(Rect{r.x, Y(r.y), r.w, r.h}, C_SKY, true);
      icons::chart(*cv, SCREEN_W / 2 - 16, Y(r.y + 56), 32, mixColor(C_SKY, C_CARD, 60));
      cv->setFont(&Fredoka16);
      cv->setTextColor(C_INK, C_CARD);
      cv->drawCenterString("No stocks yet", SCREEN_W / 2, Y(r.y + 108));
      cv->setFont(&Fredoka12);
      cv->setTextColor(C_MUTED, C_CARD);
      cv->drawCenterString("Add symbols in the app's", SCREEN_W / 2, Y(r.y + 136));
      cv->drawCenterString("Stocks tab", SCREEN_W / 2, Y(r.y + 154));
    }
    return;
  }
  if (n >= 2 && s_stockPage == 0) {
    const int gap = 3;
    const int rowH = constrain((SCREEN_H - UI_CONTENT_Y - UI_BOTTOM - gap * (n - 1)) / n, 31, 46);
    for (int i = 0; i < n; ++i) {
      const int top = UI_CONTENT_Y + i * (rowH + gap);
      if (top + rowH + 2 <= bandY || top >= bandY + bandH) continue;
      stockRow(Rect{UI_MARGIN, Y(top), SCREEN_W - UI_MARGIN * 2, rowH}, gState.stocks[idx[i]]);
    }
  } else {
    const int which = n >= 2 ? s_stockPage - 1 : 0;
    const Rect r{UI_MARGIN, UI_CONTENT_Y, SCREEN_W - UI_MARGIN * 2, SCREEN_H - UI_CONTENT_Y - UI_BOTTOM};
    if (r.y + r.h + 3 > bandY && r.y < bandY + bandH) {
      stockDetail(Rect{r.x, Y(r.y), r.w, r.h}, gState.stocks[idx[which]]);
    }
  }
  if (bandY + bandH >= SCREEN_H) pageDotsN(pages, s_stockPage);
}

bool s_diag = false;
uint32_t s_diagSince = 0;

void drawDiag() {
  const power::Diag& d = power::diag();
  cv->setFont(&Fredoka12);
  cv->setTextColor(C_INK, C_BG);
  char line[48];
  int row = 0;
  auto put = [&](const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    cv->setTextColor(C_INK, C_BG);
    cv->drawString(line, 8, Y(6 + row * 17));
    row++;
  };
  put("%s", ESPW_FW_NAME);
  put("v%d.%d.%d  build %d", ESPW_VERSION_MAJOR, ESPW_VERSION_MINOR, ESPW_VERSION_PATCH, ESPW_BUILD);
  put("ADC pin: %d mV  (vbat %.2f V)", static_cast<int>(d.rawMv), d.vbat);
  put("cell valid %d  batt %d%% %dmV", d.voltageValid ? 1 : 0, gState.battery.percent, gState.battery.millivolts);
  put("LED red %d  blue %d", d.ledRed, d.ledBlue);
  put("USB rule: adc %d  led %d  host %d", d.byAdc ? 1 : 0, d.byLed ? 1 : 0, d.byHost ? 1 : 0);
  put("USB now %d   charging %d  full %d", gState.usbPower ? 1 : 0, gState.charging ? 1 : 0, gState.chargeFull ? 1 : 0);
  put("BLE act %d conn %d pair %d", gState.bleActive ? 1 : 0, gState.bleConnected ? 1 : 0, gState.pairingActive ? 1 : 0);
  put("Wi-Fi state %d  rssi %d", gState.wifiState, gState.wifiRssi);
  put("Heap %u KB  min %u KB", static_cast<unsigned>(ESP.getFreeHeap() / 1024),
      static_cast<unsigned>(ESP.getMinFreeHeap() / 1024));
  put("Uptime %lu s  reset %d", static_cast<unsigned long>(millis() / 1000), static_cast<int>(esp_reset_reason()));
  put("Draw: %s", s_bandOk ? "band buffer" : "direct");
  put("Page %d of %d", stateCurrentPage() + 1, statePageCount());
  cv->setTextColor(C_MUTED, C_BG);
  cv->drawString("Tap to close", 8, Y(6 + row * 17));
  // Colour check: these should read red, green, blue, white. If red looks
  // blue, turn on "Fix photo colours" in the app.
  const int sy = Y(6 + row * 17);
  cv->fillRect(140, sy, 22, 14, cv->color888(255, 0, 0));
  cv->fillRect(164, sy, 22, 14, cv->color888(0, 255, 0));
  cv->fillRect(188, sy, 22, 14, cv->color888(0, 0, 255));
  cv->fillRect(212, sy, 22, 14, cv->color888(255, 255, 255));
}

// Draws every element that intersects the band [y, y+h) and pushes it.
void renderBand(int y, int h, bool headerOnly) {
  if (h <= 0) return;
  if (s_bandOk) {
    cv = &s_band;
    s_oy = y;
    s_band.fillRect(0, 0, SCREEN_W, h, C_BG);
  } else {
    cv = &tft;
    s_oy = 0;
    tft.setClipRect(0, y, SCREEN_W, h);
    tft.fillRect(0, y, SCREEN_W, h, C_BG);
  }

  if (s_diag) {
    drawDiag();
  } else if (stocksMode()) {
    if (y < UI_CONTENT_Y) header();
    if (!headerOnly) {
      drawStocksScreen(y, h);
      drawToast();
      drawPairing();
    }
  } else {
    if (y < UI_CONTENT_Y) header();
    if (!headerOnly) {
      const uint8_t page = stateCurrentPage();
      const auto& cfg = gState.layout.page[page];
      const PageGeometry g = geometry(page);
      for (int i = 0; i < g.count; ++i) {
        const Rect& r = g.slots[i];
        if (r.y + r.h + 3 <= y || r.y >= y + h) continue;
        drawWidget(cfg.slots[i], Rect{r.x, Y(r.y), r.w, r.h}, g.hero[i]);
      }
      if (y + h >= SCREEN_H) pageDots();
      drawToast();
      drawPairing();
    }
  }

  if (s_bandOk) {
    // Plain CPU-driven transfer of exactly the rows that belong to this band.
    // (The earlier DMA-based pushSprite() could overlap with the next band and
    // always sent the full sprite height, which left stale rows on screen.)
    tft.pushImage(0, y, SCREEN_W, h, reinterpret_cast<const lgfx::swap565_t*>(s_band.getBuffer()));
  }
  tft.clearClipRect();
  cv = &tft;
  s_oy = 0;
}

void renderAll() {
  themeApply(darkTheme());
  const PageGeometry g = geometry(stateCurrentPage());
  tft.startWrite();
  renderBand(0, g.bandSplit[0], false);
  renderBand(g.bandSplit[0], g.bandSplit[1] - g.bandSplit[0], false);
  renderBand(g.bandSplit[1], SCREEN_H - g.bandSplit[1], false);
  tft.endWrite();
}
}  // namespace

void uiBegin() {
  s_band.setColorDepth(16);
  s_band.setPsram(false);
  s_bandOk = s_band.createSprite(SCREEN_W, kBandMaxH) != nullptr;
  Serial.printf("[UI] band buffer %s, free heap %lu\n", s_bandOk ? "ok" : "FAILED (direct mode)",
                static_cast<unsigned long>(ESP.getFreeHeap()));
  uiApplyRotation();
}

void uiApplyRotation() {
  static int s_rotation = -1;
  if (s_rotation == gState.settings.rotation) return;
  s_rotation = gState.settings.rotation;
  tft.setRotation(gState.settings.rotation);
  gState.dirty = true;
}

// True while a charging phone-battery widget is on screen (it animates).
bool uiNeedsAnimTick() {
  if (s_diag || stocksMode() || !gState.phoneBatteryValid || !gState.phoneBattery.charging) return false;
  const auto& cfg = gState.layout.page[stateCurrentPage()];
  const int count = (cfg.template_id == espw::TemplateGrid) ? 4 : 3;
  for (int i = 0; i < count; ++i) {
    if (cfg.slots[i].type == espw::WidgetPhoneBattery) return true;
  }
  return false;
}

bool uiNeedsSecondTick() {
  if (s_diag) return true;
  if (gState.toast[0]) return true;
  const bool seconds = (gState.settings.flags & 0x08) != 0;
  if (!seconds) return false;
  const uint8_t page = stateCurrentPage();
  const auto& cfg = gState.layout.page[page];
  const int count = (cfg.template_id == espw::TemplateGrid) ? 4 : 3;
  for (int i = 0; i < count; ++i) {
    if (cfg.slots[i].type == espw::WidgetClock) return true;
  }
  return false;
}

bool uiHeaderAnimating() {
  if (s_diag) return false;
  return gState.pairingActive || (gState.bleActive && !gState.bleConnected) || gState.wifiState == 1;
}

bool uiDiagActive() { return s_diag; }

void uiToggleDiag() {
  s_diag = !s_diag;
  s_diagSince = millis();
  gState.dirty = true;
}

void uiNextPage(int delta) {
  if (delta == 0) return;
  if (stocksMode()) {
    const int pages = stockPageCount();
    s_stockPage = ((s_stockPage + delta) % pages + pages) % pages;
    gState.statusDirty = true;
    renderAll();
    gState.dirty = false;
    gState.lastActivityMs = millis();
    return;
  }
  const uint8_t count = statePageCount();
  const uint8_t oldPage = stateCurrentPage();
  int newPage = static_cast<int>(oldPage) + delta;
  while (newPage < 0) newPage += count;
  newPage %= count;
  if (newPage == oldPage) return;
  gState.settings.current_page = static_cast<uint8_t>(newPage);
  // Persist lazily: writing NVS on every tap is slow and wears the flash.
  gState.pageSaveAt = millis() + 5000;
  gState.statusDirty = true;
  renderAll();
  gState.dirty = false;
  gState.lastActivityMs = millis();
}

int uiShownPage() { return stocksMode() ? s_stockPage : stateCurrentPage(); }

void uiSetPage(int page) {
  if (stocksMode()) {
    const int pages = stockPageCount();
    s_stockPage = ((page % pages) + pages) % pages;
    gState.statusDirty = true;
  } else {
    const uint8_t count = statePageCount();
    if (page < 0 || page >= count || page == stateCurrentPage()) return;
    gState.settings.current_page = static_cast<uint8_t>(page);
    gState.pageSaveAt = millis() + 5000;
    gState.statusDirty = true;
  }
  renderAll();
  gState.dirty = false;
  gState.lastActivityMs = millis();
}

void uiSplash() {
  themeApply(darkTheme());
  tft.startWrite();
  tft.fillScreen(C_BG);
  tft.fillCircle(120, 92, 38, C_PEACH);
  tft.fillCircle(120, 92, 28, C_CARD);
  tft.fillCircle(120, 92, 12, C_SAGE);
  tft.setFont(&Fredoka28);
  tft.setTextColor(C_INK, C_BG);
  tft.drawCenterString("ESP Widget", 120, 142);
  tft.setFont(&Fredoka16);
  tft.drawCenterString("Keychain", 120, 172);
  tft.setFont(&Fredoka12);
  tft.setTextColor(C_MUTED, C_BG);
  char ver[32];
  snprintf(ver, sizeof(ver), "v%d.%d.%d  build %d", ESPW_VERSION_MAJOR, ESPW_VERSION_MINOR,
           ESPW_VERSION_PATCH, ESPW_BUILD);
  tft.drawCenterString(ver, 120, 202);
  tft.endWrite();
}

void uiRender() {
  renderAll();
  gState.dirty = false;
}

void uiRenderHeader() {
  themeApply(darkTheme());
  tft.startWrite();
  renderBand(0, UI_CONTENT_Y - 2, true);
  tft.endWrite();
}

void uiClearAndRender() { uiRender(); }

void uiSleepSplash() {
  themeApply(darkTheme());
  tft.startWrite();
  tft.fillScreen(C_BG);
  tft.setFont(&Fredoka20);
  tft.setTextColor(C_INK, C_BG);
  tft.drawCenterString("Going to sleep", 120, 96);
  tft.setFont(&Fredoka12);
  tft.setTextColor(C_MUTED, C_BG);
  tft.drawCenterString("Bluetooth and Wi-Fi are off.", 120, 138);
  tft.drawCenterString("Press the button to wake me.", 120, 158);
  tft.endWrite();
}

void uiRebootSplash() {
  tft.startWrite();
  tft.fillScreen(C_BG);
  tft.setFont(&Fredoka16);
  tft.setTextColor(C_INK, C_BG);
  tft.drawCenterString("Restarting...", 120, 110);
  tft.endWrite();
}
