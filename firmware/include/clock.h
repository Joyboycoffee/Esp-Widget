#pragma once
#include <Arduino.h>

namespace espclock {
void begin();
void sync(uint32_t utc_epoch, int16_t tz_offset_min, bool dst);
bool valid();
uint32_t epochUtc();
uint32_t epochLocal();
void localTime(int& year, int& month, int& day, int& hour, int& minute,
               int& second, int& weekday);
void formatTime(char* out, size_t cap, bool twelveHour, bool withSeconds);
void formatDate(char* out, size_t cap);
void formatShortDate(char* out, size_t cap);
void formatEpochTime(uint32_t utc, char* out, size_t cap);
void formatEpochDay(uint32_t utc, char* out, size_t cap);
}  // namespace espclock
