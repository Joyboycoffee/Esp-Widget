#include "ui.h"

#include <LittleFS.h>

#include "app_state.h"
#include "clock.h"
#include "display.h"
#include "fredoka_font.h"
#include "icons.h"
#include "net.h"
#include "power.h"
#include "theme.h"

namespace {

struct Rect {
  int x, y, w, h;
};

const char* pageName(uint8_t page) {
  static const char* names[] = {"TODAY", "MARKETS", "FOCUS", "FRAME", "CUSTOM"};
  return page < 5 ? names[page] : "CUSTOM";
}

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
  canvas.setTextColor(color, bg);
  if (canvas.textWidth(clean) <= maxW) {
    canvas.drawString(clean, x, y);
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
    if (canvas.textWidth(out) <= maxW) break;
    len--;
  }
  if (out[0]) {
    canvas.drawString(out, x, y);
  }
}

void drawCenterTruncated(const char* text, int cx, int y, int maxW, uint16_t color, uint16_t bg = C_CARD) {
  if (maxW <= 0) return;
  char clean[96];
  sanitize(text, clean, sizeof(clean));
  canvas.setTextColor(color, bg);
  if (canvas.textWidth(clean) <= maxW) {
    canvas.drawCenterString(clean, cx, y);
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
    if (canvas.textWidth(out) <= maxW) break;
    len--;
  }
  if (out[0]) {
    canvas.drawCenterString(out, cx, y);
  }
}

void wrapped(const char* text, int x, int y, int maxW, int lineH, int maxLines, uint16_t color, uint16_t bg = C_CARD) {
  if (maxW <= 0 || maxLines <= 0) return;
  char clean[120];
  sanitize(text, clean, sizeof(clean));
  canvas.setTextColor(color, bg);
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
        if (canvas.textWidth(candidate) <= maxW) {
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
  canvas.fillRoundRect(r.x, r.y + 2, r.w, r.h, 16, mixColor(C_SHADOW, C_BG, 42));
  canvas.fillRoundRect(r.x, r.y, r.w, r.h, 16, C_CARD);
  canvas.drawRoundRect(r.x, r.y, r.w, r.h, 16, C_LINE);
  const int barH = hero ? 30 : 19;
  canvas.fillRoundRect(r.x + 12, r.y + 12, 3, barH, 2, accent);
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
    canvas.drawFastHLine(area.x, mid, area.w, mixColor(accent, C_CARD, 55));
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
    if (y < bottom) canvas.drawFastVLine(area.x + x, y, bottom - y, wash(accent, 16));
    if (x > 0) canvas.drawLine(previousX, previousY, area.x + x, y, accent);
    previousX = area.x + x;
    previousY = y;
  }
  canvas.drawFastHLine(area.x, bottom, area.w, mixColor(accent, C_CARD, 35));
  canvas.fillCircle(previousX, previousY, 2, accent);
}

void header() {
  const int pct = constrain(static_cast<int>(gState.battery.percent), 0, 100);
  const uint16_t level = gState.charging || gState.chargeFull
                             ? C_GOOD
                             : pct > 50 ? C_GOOD : pct > 20 ? C_SUN : C_DANGER;

  // Clear ONLY header area (not entire screen!)
  canvas.fillRect(0, 0, SCREEN_W, UI_CONTENT_Y, C_BG);

  canvas.setFont(&Fredoka16);
  canvas.setTextColor(C_INK, C_BG);
  canvas.drawString(pageName(stateCurrentPage()), 10, 2);

  char pages[8];
  snprintf(pages, sizeof(pages), "%u/%u", stateCurrentPage() + 1, statePageCount());
  canvas.setFont(&Fredoka12);
  canvas.setTextColor(C_MUTED, C_BG);
  canvas.drawCenterString(pages, 92, 4);

  icons::bluetooth(canvas, 118, 3, 14, gState.bleConnected ? C_INK : C_LINE);

  const uint16_t wifiCol = (gState.wifiState == 2) ? C_GOOD : (net::isEnabled() ? C_INK : C_LINE);
  icons::wifi(canvas, 136, 3, 14, wifiCol);

  // Battery indicator - totally on top with zero padding!
  canvas.fillRoundRect(154, 2, 30, 14, 3, C_CARD);
  canvas.drawRoundRect(154, 2, 30, 14, 3, C_LINE);
  canvas.fillRect(184, 6, 2, 6, C_MUTED);
  const int inner = 24 * pct / 100;
  if (inner > 0) canvas.fillRoundRect(157, 5, inner, 8, 2, level);

  char battery[8];
  snprintf(battery, sizeof(battery), "%d%%", pct);
  canvas.setFont(&Fredoka12);
  canvas.setTextColor(C_INK, C_BG);
  canvas.drawString(battery, 192, 3);
}

void drawClock(const Rect& r, uint8_t accent, bool hero) {
  char time[20];
  char date[32];
  const bool twelve = (gState.settings.flags & 0x01) != 0;
  const bool seconds = (gState.settings.flags & 0x08) != 0;
  espclock::formatTime(time, sizeof(time), twelve, seconds);
  espclock::formatDate(date, sizeof(date));
  if (hero) {
    canvas.setFont(&Fredoka44);
    if (canvas.textWidth(time) > r.w - 24) canvas.setFont(&Fredoka28);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawCenterString(time, r.x + r.w / 2, r.y + 18);
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(date, r.x + r.w / 2, r.y + 74, r.w - 24, C_MUTED);
    if (seconds) {
      const int sec = espclock::valid() ? (espclock::epochLocal() % 60) : 0;
      canvas.fillRoundRect(r.x + 18, r.y + r.h - 12, r.w - 36, 4, 2, C_LINE);
      if (sec > 0) canvas.fillRoundRect(r.x + 18, r.y + r.h - 12, (r.w - 36) * sec / 60, 4, 2, accent);
    }
  } else {
    canvas.setFont(&Fredoka28);
    if (canvas.textWidth(time) > r.w - 16) canvas.setFont(&Fredoka20);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawCenterString(time, r.x + r.w / 2, r.y + 20);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(date, r.x + r.w / 2, r.y + 58, r.w - 16, C_MUTED);
  }
}

void drawWeather(const Rect& r, uint8_t accent, bool hero) {
  if (!gState.weatherValid) {
    icons::weather(canvas, 1, r.x + r.w / 2 - (hero ? 18 : 12), r.y + (hero ? 18 : 12), hero ? 36 : 24, mixColor(accent, C_CARD, 45));
    canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "No weather data" : "No weather", r.x + r.w / 2, r.y + (hero ? 62 : 44), r.w - 16, C_MUTED);
    return;
  }
  const auto& w = gState.weather;
  char temp[12];
  snprintf(temp, sizeof(temp), "%d", w.temp_c10 / 10);
  char hi[24];
  snprintf(hi, sizeof(hi), "H %d  L %d", w.hi_c10 / 10, w.lo_c10 / 10);
  if (hero) {
    icons::weather(canvas, w.code, r.x + 16, r.y + 15, 34, accent);
    canvas.setFont(&Fredoka44);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawString(temp, r.x + 56, r.y + 2);
    const int tempW = canvas.textWidth(temp);
    canvas.fillCircle(r.x + 56 + tempW + 5, r.y + 12, 3, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString("C", r.x + 56 + tempW + 12, r.y + 10);
    ellipsized(weatherLabel(w.code), r.x + 16, r.y + 49, r.w - 110, C_MUTED);
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(w.city[0] ? w.city : "Local", r.x + 16, r.y + 64, r.w - 110, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString(hi, r.x + 16, r.y + 81);
    if (w.trend_count >= 2) {
      spark(Rect{r.x + r.w - 90, r.y + 45, 74, 36}, w.trend_c10, w.trend_count, accent);
    }
  } else {
    icons::weather(canvas, w.code, r.x + 10, r.y + 10, 22, accent);
    canvas.setFont(&Fredoka20);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawString(temp, r.x + 38, r.y + 7);
    canvas.fillCircle(r.x + 38 + canvas.textWidth(temp) + 4, r.y + 11, 2, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(w.city[0] ? w.city : "Local", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    if (w.trend_count >= 2) {
      spark(Rect{r.x + 10, r.y + 54, r.w - 20, 24}, w.trend_c10, w.trend_count, accent);
    } else {
      ellipsized(weatherLabel(w.code), r.x + 10, r.y + 54, r.w - 20, C_MUTED);
      ellipsized(hi, r.x + 10, r.y + 68, r.w - 20, C_MUTED);
    }
  }
}

void drawBattery(const Rect& r, uint8_t accent, bool hero) {
  char pct[8];
  snprintf(pct, sizeof(pct), "%d%%", gState.battery.percent);
  char volts[20];
  snprintf(volts, sizeof(volts), "%.2f V", gState.battery.millivolts / 1000.0f);
  const int level = constrain(static_cast<int>(gState.battery.percent), 0, 100);
  const uint16_t statusColor = gState.charging || gState.chargeFull ? C_GOOD
                                 : gState.usbPower ? C_SKY : C_MUTED;
  const char* statusStr = gState.chargeFull ? "Full" : gState.charging ? "Charging" :
                          gState.usbPower ? "Docked" : "On battery";
  if (hero) {
    canvas.setFont(&Fredoka44);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawString(pct, r.x + 18, r.y + 7);
    icons::battery(canvas, r.x + 132, r.y + 14, 62, 30, level, accent);
    canvas.fillRoundRect(r.x + 18, r.y + 58, r.w - 36, 7, 3, C_LINE);
    if (level > 0) canvas.fillRoundRect(r.x + 18, r.y + 58, (r.w - 36) * level / 100, 7, 3, accent);
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(statusColor, C_CARD);
    ellipsized(statusStr, r.x + 18, r.y + 70, r.w - 96, statusColor);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString(volts, r.x + r.w - 18 - canvas.textWidth(volts), r.y + 72);
  } else {
    canvas.setFont(&Fredoka20);
    canvas.setTextColor(C_INK, C_CARD);
    canvas.drawString(pct, r.x + 10, r.y + 10);
    icons::battery(canvas, r.x + r.w - 38, r.y + 12, 28, 14, level, accent);
    canvas.fillRoundRect(r.x + 10, r.y + 36, r.w - 20, 4, 2, C_LINE);
    if (level > 0) canvas.fillRoundRect(r.x + 10, r.y + 36, (r.w - 20) * level / 100, 4, 2, accent);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(statusColor, C_CARD);
    ellipsized(statusStr, r.x + 10, r.y + 48, r.w - 20, statusColor);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(volts, r.x + 10, r.y + 66, r.w - 20, C_MUTED);
  }
}

void drawStocks(const Rect& r, uint8_t accent, bool hero, uint8_t option) {
  const uint8_t idx = option % 6;
  if (!gState.stockValid[idx]) {
    icons::chart(canvas, r.x + r.w / 2 - 12, r.y + (hero ? 18 : 12), 24, mixColor(accent, C_CARD, 45));
    canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "Add stocks in app" : "Add stocks", r.x + r.w / 2, r.y + (hero ? 52 : 44), r.w - 16, C_MUTED);
    if (!hero) {
      drawCenterTruncated("in the app", r.x + r.w / 2, r.y + 60, r.w - 16, C_MUTED);
    }
    return;
  }
  const auto& s = gState.stocks[idx];
  char sym[13];
  sanitize(s.symbol, sym, sizeof(sym));
  const float price = s.price_cents / 100.0f;
  char priceStr[20];
  if (s.currency == 0) snprintf(priceStr, sizeof(priceStr), "Rs %.2f", price);
  else snprintf(priceStr, sizeof(priceStr), "$%.2f", price);
  char change[16];
  snprintf(change, sizeof(change), "%+.2f%%", s.change_bp / 100.0f);
  const uint16_t trend = s.change_bp >= 0 ? C_GOOD : C_DANGER;
  if (hero) {
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(sym, r.x + 18, r.y + 9, 80, C_MUTED);
    canvas.setFont(&Fredoka28);
    canvas.setTextColor(C_INK, C_CARD);
    if (canvas.textWidth(priceStr) > 86) canvas.setFont(&Fredoka20);
    ellipsized(priceStr, r.x + 18, r.y + 22, 86, C_INK);
    canvas.fillRoundRect(r.x + 18, r.y + 56, 58, 17, 8, trend);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_CARD, trend);
    canvas.drawCenterString(change, r.x + 47, r.y + 59);
    icons::arrow(canvas, r.x + 82, r.y + 60, 12, trend, s.change_bp >= 0);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString(s.market == 1 ? "LIVE · 1M" : "CLOSED · 1M", r.x + 18, r.y + 80);
    spark(Rect{r.x + 108, r.y + 35, r.w - 126, 52}, s.spark, 24, trend);
  } else {
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(sym, r.x + 10, r.y + 8, r.w - 20, C_MUTED);
    canvas.setFont(&Fredoka20);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(priceStr, r.x + 10, r.y + 22, r.w - 20, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(trend, C_CARD);
    ellipsized(change, r.x + 10, r.y + 44, r.w - 20, trend);
    spark(Rect{r.x + 10, r.y + 60, r.w - 20, 19}, s.spark, 24, trend);
  }
}

void drawNotifications(const Rect& r, uint8_t accent, bool hero) {
  if (!gState.notificationValid) {
    icons::bell(canvas, r.x + r.w / 2 - 11, r.y + (hero ? 20 : 14), 22, mixColor(accent, C_CARD, 45));
    canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated("All quiet", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& n = gState.notification;
  char app[17], title[41], body[57];
  sanitize(n.app, app, sizeof(app));
  sanitize(n.title, title, sizeof(title));
  sanitize(n.body, body, sizeof(body));
  if (hero) {
    icons::bell(canvas, r.x + 16, r.y + 12, 28, accent);
    canvas.setFont(&Fredoka28);
    canvas.setTextColor(C_INK, C_CARD);
    char count[8];
    snprintf(count, sizeof(count), "%d", n.count);
    canvas.drawString(count, r.x + 50, r.y + 10);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString("new", r.x + 76, r.y + 22);
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(app[0] ? app : "Notification", r.x + 20, r.y + 48, r.w - 40, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    wrapped(title[0] ? title : body, r.x + 20, r.y + 68, r.w - 40, 14, 2, C_INK);
  } else {
    icons::bell(canvas, r.x + 10, r.y + 10, 20, accent);
    canvas.setFont(&Fredoka20);
    canvas.setTextColor(C_INK, C_CARD);
    char count[8];
    snprintf(count, sizeof(count), "%d", n.count);
    canvas.drawString(count, r.x + 36, r.y + 8);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(app[0] ? app : "Notice", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    ellipsized(title[0] ? title : body, r.x + 10, r.y + 52, r.w - 20, C_INK);
    ellipsized(body, r.x + 10, r.y + 68, r.w - 20, C_MUTED);
  }
}

void drawMedia(const Rect& r, uint8_t accent, bool hero) {
  if (!gState.mediaValid) {
    icons::play(canvas, r.x + r.w / 2 - 12, r.y + (hero ? 20 : 14), 24, mixColor(accent, C_CARD, 45), false);
    canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    drawCenterTruncated(hero ? "Nothing playing" : "No media", r.x + r.w / 2, r.y + (hero ? 56 : 46), r.w - 16, C_MUTED);
    return;
  }
  const auto& m = gState.media;
  char title[41], artist[25];
  sanitize(m.title, title, sizeof(title));
  sanitize(m.artist, artist, sizeof(artist));
  if (hero) {
    canvas.fillCircle(r.x + 40, r.y + 38, 22, accent);
    icons::play(canvas, r.x + 29, r.y + 26, 24, C_INK, m.playing != 0);
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Unknown track", r.x + 72, r.y + 14, r.w - 86, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(artist[0] ? artist : "Unknown artist", r.x + 72, r.y + 36, r.w - 86, C_MUTED);
    canvas.fillRoundRect(r.x + 72, r.y + 58, r.w - 86, 5, 2, C_LINE);
    canvas.fillRoundRect(r.x + 72, r.y + 58, (r.w - 86) * constrain(m.progress_pct, 0, 100) / 100, 5, 2, accent);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(m.playing ? "NOW PLAYING" : "PAUSED", r.x + 20, r.y + r.h - 18, r.w - 40, C_MUTED);
  } else {
    icons::play(canvas, r.x + 10, r.y + 10, 18, accent, m.playing != 0);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Unknown track", r.x + 34, r.y + 12, r.w - 44, C_INK);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(artist[0] ? artist : "Unknown artist", r.x + 10, r.y + 36, r.w - 20, C_MUTED);
    canvas.fillRoundRect(r.x + 10, r.y + 58, r.w - 20, 4, 2, C_LINE);
    canvas.fillRoundRect(r.x + 10, r.y + 58, (r.w - 20) * constrain(m.progress_pct, 0, 100) / 100, 4, 2, accent);
    ellipsized(m.playing ? "Playing" : "Paused", r.x + 10, r.y + 68, r.w - 20, C_MUTED);
  }
}

void drawCalendar(const Rect& r, uint8_t accent, bool hero) {
  if (!gState.calendarValid) {
    icons::calendar(canvas, r.x + r.w / 2 - 12, r.y + (hero ? 20 : 14), 24, mixColor(accent, C_CARD, 45));
    canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
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
    canvas.fillRoundRect(r.x + 14, r.y + 14, 52, 54, 10, accent);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_INK, accent);
    String d = String(day);
    int spaceIdx = d.indexOf(' ');
    if (spaceIdx >= 0) {
      canvas.drawCenterString(d.substring(0, spaceIdx).c_str(), r.x + 40, r.y + 20);
      canvas.setFont(&Fredoka20);
      canvas.drawCenterString(d.substring(spaceIdx + 1).c_str(), r.x + 40, r.y + 38);
    } else {
      canvas.drawCenterString(day, r.x + 40, r.y + 24);
    }
    canvas.setFont(&Fredoka16);
    canvas.setTextColor(C_INK, C_CARD);
    wrapped(title[0] ? title : "Untitled event", r.x + 76, r.y + 14, r.w - 90, 18, 2, C_INK);
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_MUTED, C_CARD);
    canvas.drawString(c.all_day ? "All day" : time, r.x + 76, r.y + 56);
    ellipsized(loc, r.x + 76, r.y + 74, r.w - 90, C_MUTED);
  } else {
    canvas.fillRoundRect(r.x + 8, r.y + 10, 36, 36, 8, accent);
    canvas.setTextColor(C_INK, accent);
    String d = String(day);
    int sp = d.indexOf(' ');
    if (sp >= 0) {
      canvas.setFont(&Fredoka16);
      canvas.drawCenterString(d.substring(0, sp).c_str(), r.x + 26, r.y + 13);
      canvas.setFont(&Fredoka12);
      canvas.drawCenterString(d.substring(sp + 1).c_str(), r.x + 26, r.y + 28);
    } else {
      canvas.setFont(&Fredoka12);
      canvas.drawCenterString(day, r.x + 26, r.y + 18);
    }
    canvas.setFont(&Fredoka12);
    canvas.setTextColor(C_INK, C_CARD);
    ellipsized(title[0] ? title : "Event", r.x + 48, r.y + 12, r.w - 56, C_INK);
    canvas.setTextColor(C_MUTED, C_CARD);
    ellipsized(c.all_day ? "All day" : time, r.x + 48, r.y + 30, r.w - 56, C_MUTED);
    ellipsized(loc, r.x + 10, r.y + 54, r.w - 20, C_MUTED);
  }
}

void drawText(const Rect& r, uint8_t accent, bool hero, uint8_t slot) {
  const uint8_t idx = slot % 4;
  char text[97];
  sanitize(gState.texts[idx], text, sizeof(text));
  if (hero) {
    icons::quote(canvas, r.x + 14, r.y + 8, 22, accent);
    canvas.setFont(&Fredoka16);
    wrapped(text[0] ? text : "Add a note in the app", r.x + 18, r.y + 32, r.w - 36, 18, 3, C_INK);
  } else {
    icons::quote(canvas, r.x + 10, r.y + 8, 18, accent);
    canvas.setFont(&Fredoka12);
    wrapped(text[0] ? text : "Add note in app", r.x + 10, r.y + 30, r.w - 20, 14, 3, C_INK);
  }
}

void drawPhoto(const Rect& r, uint8_t accent, bool hero) {
  if (gState.photoReady) {
    canvas.fillRoundRect(r.x, r.y, r.w, r.h, 14, C_LINE);
    canvas.drawJpgFile("/photo.jpg", r.x + 2, r.y + 2, r.w - 4, r.h - 4);
    canvas.drawRoundRect(r.x, r.y, r.w, r.h, 14, C_CARD);
    return;
  }
  icons::photo(canvas, r.x + r.w / 2 - (hero ? 20 : 14), r.y + (hero ? 18 : 12), hero ? 40 : 28, accent);
  canvas.setFont(hero ? &Fredoka16 : &Fredoka12);
  canvas.setTextColor(C_MUTED, C_CARD);
  drawCenterTruncated("Send a photo", r.x + r.w / 2, r.y + r.h - (hero ? 30 : 24), r.w - 16, C_MUTED);
}

void drawWidget(const espw::SlotConfig& slot, const Rect& r, bool hero) {
  const uint16_t accent = accentColor(slot.accent);
  card(r, accent, hero);
  canvas.setClipRect(r.x + 2, r.y + 2, r.w - 4, r.h - 4);
  switch (slot.type) {
    case espw::WidgetClock: drawClock(r, accent, hero); break;
    case espw::WidgetWeather: drawWeather(r, accent, hero); break;
    case espw::WidgetBattery: drawBattery(r, accent, hero); break;
    case espw::WidgetStocks: drawStocks(r, accent, hero, slot.option); break;
    case espw::WidgetNotifications: drawNotifications(r, accent, hero); break;
    case espw::WidgetMedia: drawMedia(r, accent, hero); break;
    case espw::WidgetCalendar: drawCalendar(r, accent, hero); break;
    case espw::WidgetSteps: drawBattery(r, accent, hero); break;
    case espw::WidgetText: drawText(r, accent, hero, slot.option); break;
    case espw::WidgetPhoto: drawPhoto(r, accent, hero); break;
    default: {
      canvas.setFont(&Fredoka12);
      canvas.setTextColor(C_MUTED, C_CARD);
      drawCenterTruncated("Empty", r.x + r.w / 2, r.y + r.h / 2 - 6, r.w - 16, C_MUTED);
      break;
    }
  }
  canvas.clearClipRect();
}

void drawPage(uint8_t page, int offsetX) {
  const auto& cfg = gState.layout.page[page];
  if (cfg.template_id == espw::TemplateGrid) {
    const int w = (SCREEN_W - UI_MARGIN * 2 - UI_GAP) / 2;
    const int h = (SCREEN_H - UI_CONTENT_Y - UI_MARGIN - UI_GAP) / 2;
    for (int i = 0; i < 4; ++i) {
      Rect r{UI_MARGIN + (i % 2) * (w + UI_GAP) + offsetX,
             UI_CONTENT_Y + (i / 2) * (h + UI_GAP), w, h};
      if (r.x + r.w > 0 && r.x < SCREEN_W) {
        drawWidget(cfg.slots[i], r, false);
      }
    }
  } else {
    Rect hero{UI_MARGIN + offsetX, UI_CONTENT_Y, SCREEN_W - UI_MARGIN * 2, UI_HERO_H};
    if (hero.x + hero.w > 0 && hero.x < SCREEN_W) {
      drawWidget(cfg.slots[0], hero, true);
    }
    const int w = (SCREEN_W - UI_MARGIN * 2 - UI_GAP) / 2;
    Rect left{UI_MARGIN + offsetX, UI_CONTENT_Y + UI_HERO_H + UI_GAP, w, UI_SMALL_H};
    Rect right{UI_MARGIN + w + UI_GAP + offsetX, UI_CONTENT_Y + UI_HERO_H + UI_GAP, w, UI_SMALL_H};
    if (left.x + left.w > 0 && left.x < SCREEN_W) {
      drawWidget(cfg.slots[1], left, false);
    }
    if (right.x + right.w > 0 && right.x < SCREEN_W) {
      drawWidget(cfg.slots[2], right, false);
    }
  }
}

void drawToast() {
  if (!gState.toast[0] || millis() > gState.toastUntil) return;
  char clean[49];
  sanitize(gState.toast, clean, sizeof(clean));
  canvas.setFont(&Fredoka12);
  int w = static_cast<int>(canvas.textWidth(clean)) + 28;
  if (w > 220) w = 220;
  const int x = (SCREEN_W - w) / 2;
  canvas.fillRoundRect(x + 2, 199, w, 30, 15, C_SHADOW);
  canvas.fillRoundRect(x, 197, w, 30, 15, C_INK);
  drawCenterTruncated(clean, x + w / 2, 203, w - 16, C_CARD, C_INK);
}

void drawPairing() {
  if (!gState.pairingActive) return;
  canvas.fillRoundRect(14, 20, 212, 200, 20, C_SHADOW);
  canvas.fillRoundRect(12, 18, 212, 200, 20, C_CARD);
  canvas.drawRoundRect(12, 18, 212, 200, 20, C_LINE);
  canvas.setFont(&Fredoka16);
  canvas.setTextColor(C_INK, C_CARD);
  canvas.drawCenterString("PAIR WITH PHONE", 118, 34);
  canvas.setFont(&Fredoka12);
  canvas.setTextColor(C_MUTED, C_CARD);
  char pin[8];
  snprintf(pin, sizeof(pin), "%06lu", static_cast<unsigned long>(gState.pairingPasskey));
  canvas.setFont(&Fredoka44);
  canvas.setTextColor(C_INK, C_CARD);
  canvas.drawCenterString(pin, 118, 64);
  canvas.setFont(&Fredoka12);
  canvas.setTextColor(C_MUTED, C_CARD);
  canvas.drawCenterString("Enter this code in the app", 118, 126);
  canvas.drawCenterString("Bluetooth stays bonded", 118, 144);
  canvas.setFont(&Fredoka16);
  canvas.setTextColor(C_INK, C_CARD);
  canvas.drawCenterString(ESPW_BLE_NAME_PREFIX, 118, 174);
}
}  // namespace

void uiBegin() {
  canvas.fillScreen(C_BG);
  uiApplyRotation();
}

void uiApplyRotation() {
  tft.setRotation(gState.settings.rotation);
}

bool uiNeedsSecondTick() {
  if (gState.pairingActive || gState.toast[0]) return true;
  const bool seconds = (gState.settings.flags & 0x08) != 0;
  if (!seconds) return false;
  const uint8_t page = stateCurrentPage();
  const auto& cfg = gState.layout.page[page];
  const int count = (cfg.template_id == espw::TemplateGrid) ? 4 : 3;
  for (int i = 0; i < count; ++i) {
    if (cfg.slots[i].type == espw::WidgetClock) {
      return true;
    }
  }
  return false;
}

void uiNextPage(int delta) {
  if (delta == 0) return;
  const uint8_t count = statePageCount();
  const uint8_t oldPage = stateCurrentPage();
  int newPage = static_cast<int>(oldPage) + delta;
  while (newPage < 0) newPage += count;
  newPage %= count;
  if (newPage == oldPage) return;

  const uint8_t curBrightness = power::brightness();

  // Snappy, smooth fade down (~30ms) to hide intermediate card drawing
  for (int i = 3; i >= 1; --i) {
    tft.setBrightness(static_cast<uint8_t>(curBrightness * i / 4));
    delay(10);
  }
  tft.setBrightness(0);

  // Switch page and render cleanly at standard coordinates while dark
  gState.settings.current_page = static_cast<uint8_t>(newPage);
  stateSaveSettings();

  tft.startWrite();
  canvas.fillScreen(C_BG);
  header();
  drawPage(newPage, 0);
  drawToast();
  drawPairing();
  tft.endWrite();

  // Snappy, smooth fade up (~30ms)
  for (int i = 1; i <= 4; ++i) {
    tft.setBrightness(static_cast<uint8_t>(curBrightness * i / 4));
    delay(10);
  }
  tft.setBrightness(curBrightness);

  gState.dirty = false;
  gState.lastActivityMs = millis();
}

void uiSplash() {
  tft.startWrite();
  canvas.fillScreen(C_BG);
  canvas.fillCircle(120, 92, 38, C_PEACH);
  canvas.fillCircle(120, 92, 28, C_CARD);
  canvas.fillCircle(120, 92, 12, C_SAGE);
  canvas.setFont(&Fredoka28);
  canvas.setTextColor(C_INK, C_BG);
  canvas.drawCenterString("ESP Widget", 120, 146);
  canvas.setFont(&Fredoka12);
  canvas.setTextColor(C_MUTED, C_BG);
  canvas.drawCenterString("little moments, always on", 120, 182);
  tft.endWrite();
}

static bool s_toastWasActive = false;

void uiRender() {
  tft.startWrite();

  // If a toast was previously visible and has expired, clear its area back to background
  const bool toastActive = (gState.toast[0] != 0 && millis() <= gState.toastUntil);
  if (s_toastWasActive && !toastActive) {
    canvas.fillRect(0, 195, SCREEN_W, 35, C_BG);
    s_toastWasActive = false;
  }

  // Never fillScreen(C_BG) during routine updates - eliminates the 1s flash!
  header();
  const uint8_t page = stateCurrentPage();
  drawPage(page, 0);
  if (toastActive) {
    drawToast();
    s_toastWasActive = true;
  }
  drawPairing();
  tft.endWrite();
  gState.dirty = false;
}

void uiClearAndRender() {
  tft.startWrite();
  canvas.fillScreen(C_BG);
  header();
  const uint8_t page = stateCurrentPage();
  drawPage(page, 0);
  drawToast();
  drawPairing();
  tft.endWrite();
  gState.dirty = false;
}

void uiRebootSplash() {
  tft.startWrite();
  canvas.fillScreen(C_BG);
  canvas.setFont(&Fredoka16);
  canvas.setTextColor(C_INK, C_BG);
  canvas.drawCenterString("Restarting...", 120, 110);
  tft.endWrite();
}
