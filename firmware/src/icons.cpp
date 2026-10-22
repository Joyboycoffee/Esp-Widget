#include "icons.h"

#include <math.h>

namespace icons {
namespace {
int mid(int x, int y, int s) { return x + s / 2; }
void rays(LovyanGFX& g, int cx, int cy, int r, int s, uint16_t c) {
  for (int i = 0; i < 8; ++i) {
    const float a = i * 0.785398f;
    const int x1 = cx + static_cast<int>(cosf(a) * (r + s / 10));
    const int y1 = cy + static_cast<int>(sinf(a) * (r + s / 10));
    const int x2 = cx + static_cast<int>(cosf(a) * (r + s / 4));
    const int y2 = cy + static_cast<int>(sinf(a) * (r + s / 4));
    g.drawLine(x1, y1, x2, y2, c);
  }
}
}  // namespace

void sun(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  const int cx = mid(x, y, s);
  const int cy = mid(x, y, s);
  g.fillCircle(cx, cy, s / 5, color);
  rays(g, cx, cy, s / 5, s, color);
}

void cloud(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  const int baseY = y + s * 3 / 5;
  g.fillCircle(x + s / 4, baseY - s / 8, s / 4, color);
  g.fillCircle(x + s / 2, baseY - s / 4, s / 3, color);
  g.fillCircle(x + s * 3 / 4, baseY - s / 10, s / 4, color);
  g.fillRoundRect(x + s / 10, baseY - s / 10, s * 8 / 10, s / 3, s / 10, color);
}

void rain(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  cloud(g, x, y - s / 8, s, color);
  for (int i = 0; i < 3; ++i) {
    const int xx = x + s / 4 + i * s / 4;
    g.drawLine(xx, y + s * 3 / 5, xx - s / 12, y + s * 4 / 5, color);
  }
}

void snow(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  cloud(g, x, y - s / 8, s, color);
  for (int i = 0; i < 3; ++i) {
    const int cx = x + s / 4 + i * s / 4;
    const int cy = y + s * 3 / 4;
    g.drawLine(cx - 3, cy, cx + 3, cy, color);
    g.drawLine(cx, cy - 3, cx, cy + 3, color);
  }
}

void storm(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  cloud(g, x, y - s / 8, s, color);
  const int cx = x + s / 2;
  g.fillTriangle(cx + s / 10, y + s / 2, cx - s / 5, y + s * 3 / 4, cx, y + s * 3 / 4, color);
  g.fillTriangle(cx, y + s * 3 / 5, cx - s / 10, y + s * 4 / 5, cx + s / 8, y + s * 4 / 5, color);
}

void fog(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  for (int i = 0; i < 4; ++i) {
    const int yy = y + s / 3 + i * s / 7;
    g.drawLine(x + (i % 2 ? s / 6 : 0), yy, x + s - (i % 2 ? 0 : s / 6), yy, color);
  }
}

void weather(LovyanGFX& g, int code, int x, int y, int s, uint16_t color) {
  if (code == 0) return sun(g, x, y, s, color);
  if (code <= 3) return cloud(g, x, y, s, color);
  if (code == 45 || code == 48) return fog(g, x, y, s, color);
  if (code >= 95) return storm(g, x, y, s, color);
  if ((code >= 71 && code <= 77) || code == 85 || code == 86) return snow(g, x, y, s, color);
  return rain(g, x, y, s, color);
}

void battery(LovyanGFX& g, int x, int y, int w, int h, int percent, uint16_t color) {
  g.drawRoundRect(x, y, w, h, 4, color);
  g.drawRoundRect(x + 1, y + 1, w - 2, h - 2, 3, color);
  g.fillRect(x + w, y + h / 3, 3, h / 3, color);
  const int inner = (w - 6) * (percent < 0 ? 0 : percent > 100 ? 100 : percent) / 100;
  if (inner > 0) g.fillRoundRect(x + 3, y + 3, inner, h - 6, 2, color);
}

void bell(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  const int cx = mid(x, y, s);
  g.fillCircle(cx, y + s / 4, s / 5, color);
  g.fillTriangle(cx - s / 3, y + s * 3 / 5, cx + s / 3, y + s * 3 / 5, cx, y + s / 2, color);
  g.fillRoundRect(cx - s / 3, y + s * 2 / 5, s * 2 / 3, s / 3, s / 6, color);
  g.fillCircle(cx, y + s * 4 / 5, s / 12, color);
}

void play(LovyanGFX& g, int x, int y, int s, uint16_t color, bool playing) {
  if (playing) {
    g.fillRoundRect(x + s / 5, y + s / 5, s / 5, s * 3 / 5, 2, color);
    g.fillRoundRect(x + s * 3 / 5, y + s / 5, s / 5, s * 3 / 5, 2, color);
  } else {
    g.fillTriangle(x + s / 3, y + s / 5, x + s / 3, y + s * 4 / 5, x + s * 4 / 5, y + s / 2, color);
  }
}

void calendar(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  g.drawRoundRect(x, y + s / 8, s, s * 7 / 8, 3, color);
  g.drawFastHLine(x, y + s / 3, s, color);
  g.drawLine(x + s / 4, y, x + s / 4, y + s / 5, color);
  g.drawLine(x + s * 3 / 4, y, x + s * 3 / 4, y + s / 5, color);
  for (int r = 0; r < 2; ++r) {
    for (int c = 0; c < 3; ++c) {
      g.fillCircle(x + s / 4 + c * s / 4, y + s / 2 + r * s / 4, 1, color);
    }
  }
}

void steps(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  g.fillEllipse(x + s / 3, y + s / 3, s / 8, s / 5, color);
  g.fillEllipse(x + s * 2 / 3, y + s / 2, s / 8, s / 5, color);
  g.fillCircle(x + s / 5, y + s / 4, s / 10, color);
  g.fillCircle(x + s * 3 / 4, y + s * 2 / 5, s / 10, color);
}

void chart(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  g.drawLine(x, y + s, x + s, y + s, color);
  g.drawLine(x, y, x, y + s, color);
  int py = y + s * 3 / 4;
  for (int i = 0; i < 4; ++i) {
    const int ny = y + s / 5 + (i % 2 ? s / 6 : s / 3);
    g.drawLine(x + i * s / 5, py, x + (i + 1) * s / 5, ny, color);
    py = ny;
  }
}

void bluetooth(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  const int cx = mid(x, y, s);
  g.drawLine(cx, y + s / 6, cx, y + s * 5 / 6, color);
  g.drawLine(cx, y + s / 6, cx + s / 4, y + s / 3, color);
  g.drawLine(cx + s / 4, y + s / 3, cx - s / 4, y + s * 2 / 3, color);
  g.drawLine(cx, y + s * 5 / 6, cx + s / 4, y + s * 2 / 3, color);
  g.drawLine(cx + s / 4, y + s * 2 / 3, cx - s / 4, y + s / 3, color);
}

void wifi(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  const int cx = mid(x, y, s);
  const int cy = y + s * 3 / 4;
  g.fillCircle(cx, cy, s / 10, color);
  g.drawArc(cx, cy, s / 4, s / 4 + 2, 225, 315, color);
  g.drawArc(cx, cy, s * 2 / 5, s * 2 / 5 + 2, 225, 315, color);
}

void arrow(LovyanGFX& g, int x, int y, int s, uint16_t color, bool up) {
  const int cy = mid(x, y, s / 2);
  g.drawLine(x, cy, x + s, cy, color);
  if (up) g.fillTriangle(x + s, y, x + s, y + s / 2, x + s * 3 / 4, y + s / 4, color);
  else g.fillTriangle(x + s, y + s / 2, x + s, y + s, x + s * 3 / 4, y + s * 3 / 4, color);
}

void photo(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  g.drawRoundRect(x, y, s, s * 3 / 4, 3, color);
  g.fillCircle(x + s / 3, y + s / 4, s / 12, color);
  g.fillTriangle(x + s / 5, y + s * 3 / 4, x + s / 2, y + s / 3, x + s * 4 / 5, y + s * 3 / 4, color);
}

void quote(LovyanGFX& g, int x, int y, int s, uint16_t color) {
  g.fillCircle(x + s / 4, y + s / 3, s / 8, color);
  g.fillCircle(x + s * 3 / 4, y + s / 3, s / 8, color);
  g.fillTriangle(x + s / 8, y + s / 3, x + s * 3 / 8, y + s / 3, x + s / 4, y + s * 2 / 3, color);
  g.fillTriangle(x + s * 5 / 8, y + s / 3, x + s * 7 / 8, y + s / 3, x + s * 3 / 4, y + s * 2 / 3, color);
}
}  // namespace icons
