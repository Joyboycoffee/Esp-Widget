#include "clock.h"

#include <time.h>

namespace espclock {
namespace {
bool s_valid = false;
uint32_t s_baseEpoch = 0;
uint32_t s_baseMillis = 0;
int16_t s_tzMinutes = 0;
bool s_dst = false;

void civilFromDays(int64_t z, int& y, int& m, int& d) {
  z += 719468;
  const int64_t era = (z >= 0 ? z : z - 146096) / 146097;
  const unsigned doe = static_cast<unsigned>(z - era * 146097);
  const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  y = static_cast<int>(yoe) + static_cast<int>(era) * 400;
  const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  const unsigned mp = (5 * doy + 2) / 153;
  d = static_cast<int>(doy - (153 * mp + 2) / 5 + 1);
  m = static_cast<int>(mp < 10 ? mp + 3 : mp - 9);
  if (m <= 2) y++;
}
}  // namespace

void begin() {
  s_valid = false;
  s_baseEpoch = 0;
  s_baseMillis = millis();
  s_tzMinutes = 0;
  s_dst = false;
}

void sync(uint32_t utc_epoch, int16_t tz_offset_min, bool dst) {
  if (utc_epoch < 1600000000UL) return;
  s_baseEpoch = utc_epoch;
  s_baseMillis = millis();
  s_tzMinutes = tz_offset_min;
  s_dst = dst && tz_offset_min != 0;
  s_valid = true;
}

bool valid() { return s_valid; }

uint32_t epochUtc() {
  if (!s_valid) return 0;
  return s_baseEpoch + (millis() - s_baseMillis) / 1000UL;
}

uint32_t epochLocal() {
  if (!s_valid) return 0;
  return epochUtc() + static_cast<int32_t>(s_tzMinutes) * 60;
}

void localTime(int& year, int& month, int& day, int& hour, int& minute,
               int& second, int& weekday) {
  const uint32_t local = epochLocal();
  const int64_t days = local / 86400UL;
  const int sod = static_cast<int>(local % 86400UL);
  civilFromDays(days, year, month, day);
  hour = sod / 3600;
  minute = (sod % 3600) / 60;
  second = sod % 60;
  weekday = static_cast<int>((days + 4) % 7);  // 0 = Sunday
  if (weekday < 0) weekday += 7;
}

void formatTime(char* out, size_t cap, bool twelveHour, bool withSeconds) {
  int y, mo, d, h, mi, s, wd;
  if (!s_valid) {
    snprintf(out, cap, withSeconds ? "--:--:--" : "--:--");
    return;
  }
  localTime(y, mo, d, h, mi, s, wd);
  if (twelveHour) {
    const bool pm = h >= 12;
    int hh = h % 12;
    if (hh == 0) hh = 12;
    if (withSeconds) {
      snprintf(out, cap, "%d:%02d:%02d %s", hh, mi, s, pm ? "PM" : "AM");
    } else {
      snprintf(out, cap, "%d:%02d %s", hh, mi, pm ? "PM" : "AM");
    }
  } else if (withSeconds) {
    snprintf(out, cap, "%02d:%02d:%02d", h, mi, s);
  } else {
    snprintf(out, cap, "%02d:%02d", h, mi);
  }
}

void formatDate(char* out, size_t cap) {
  static const char* days[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
  static const char* months[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                 "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  int y, mo, d, h, mi, s, wd;
  if (!s_valid) {
    snprintf(out, cap, "Waiting for sync");
    return;
  }
  localTime(y, mo, d, h, mi, s, wd);
  snprintf(out, cap, "%s, %d %s", days[wd], d, months[mo - 1]);
}

void formatShortDate(char* out, size_t cap) {
  int y, mo, d, h, mi, s, wd;
  if (!s_valid) {
    snprintf(out, cap, "--");
    return;
  }
  localTime(y, mo, d, h, mi, s, wd);
  snprintf(out, cap, "%02d/%02d", d, mo);
}

void formatEpochTime(uint32_t utc, char* out, size_t cap) {
  const uint32_t local = utc + static_cast<int32_t>(s_tzMinutes) * 60;
  const int sod = static_cast<int>(local % 86400UL);
  snprintf(out, cap, "%02d:%02d", sod / 3600, (sod % 3600) / 60);
}

void formatEpochDay(uint32_t utc, char* out, size_t cap) {
  const uint32_t local = utc + static_cast<int32_t>(s_tzMinutes) * 60;
  const int64_t days = local / 86400UL;
  int y, mo, d;
  civilFromDays(days, y, mo, d);
  snprintf(out, cap, "%02d %s", d, mo == 1 ? "JAN" : mo == 2 ? "FEB" : mo == 3 ? "MAR" :
           mo == 4 ? "APR" : mo == 5 ? "MAY" : mo == 6 ? "JUN" : mo == 7 ? "JUL" :
           mo == 8 ? "AUG" : mo == 9 ? "SEP" : mo == 10 ? "OCT" : mo == 11 ? "NOV" : "DEC");
}

}  // namespace espclock
