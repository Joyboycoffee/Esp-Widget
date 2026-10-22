#include "display.h"

#include <driver/gpio.h>
#include <esp_sleep.h>

#include "app_state.h"
#include "theme.h"

LGFX_ST7789 tft;
LGFX_ST7789& canvas = tft;

void displayBegin() {
  tft.init();
  tft.setRotation(gState.settings.rotation);
  tft.setBrightness(0);
  tft.startWrite();
  tft.fillScreen(C_BG);
  tft.endWrite();

  Serial.printf("[DISPLAY] 16-bit direct RGB565 active. Free heap: %lu\n",
                static_cast<unsigned long>(ESP.getFreeHeap()));
}

void displayFadeIn(uint8_t target) {
  if (target == 0) return;
  for (int i = 1; i <= 20; ++i) {
    tft.setBrightness(static_cast<uint8_t>(target * i / 20));
    delay(8);
  }
  tft.setBrightness(target);
}

void displayFadeOut(uint8_t from) {
  if (from == 0) return;
  for (int i = 19; i >= 0; --i) {
    tft.setBrightness(static_cast<uint8_t>(from * i / 20));
    delay(8);
  }
  tft.setBrightness(0);
}

void displaySleepHold() {
  tft.setBrightness(0);
  gpio_hold_en(static_cast<gpio_num_t>(PIN_TFT_BL));
  gpio_hold_en(static_cast<gpio_num_t>(PIN_TFT_RES));
  gpio_hold_en(static_cast<gpio_num_t>(PIN_TFT_DC));
#if PIN_DEBUG_LED >= 0
  gpio_hold_en(static_cast<gpio_num_t>(PIN_DEBUG_LED));
#endif
  gpio_deep_sleep_hold_en();
}
