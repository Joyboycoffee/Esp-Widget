#include "display.h"

#include <driver/gpio.h>
#include <esp_sleep.h>

#include "app_state.h"
#include "theme.h"

LGFX_ST7789 tft;

void displayBegin() {
  // Deep sleep latches the backlight/reset/DC pins. Release them before the
  // panel is initialised, otherwise the backlight stays stuck after waking.
  gpio_deep_sleep_hold_dis();
  gpio_hold_dis(static_cast<gpio_num_t>(PIN_TOUCH));
  gpio_hold_dis(static_cast<gpio_num_t>(PIN_TFT_BL));
  gpio_hold_dis(static_cast<gpio_num_t>(PIN_TFT_RES));
  gpio_hold_dis(static_cast<gpio_num_t>(PIN_TFT_DC));
#if PIN_DEBUG_LED >= 0
  gpio_hold_dis(static_cast<gpio_num_t>(PIN_DEBUG_LED));
#endif
  tft.init();
  tft.setRotation(gState.settings.rotation);
  tft.setBrightness(0);
  tft.startWrite();
  tft.fillScreen(C_BG);
  tft.endWrite();

  Serial.printf("[DISPLAY] ready. Free heap: %lu\n",
                static_cast<unsigned long>(ESP.getFreeHeap()));
}

// Plugging in / unplugging the charger can glitch the panel (a supply dip
// while the TP4056 switches over), leaving a frozen picture even though the
// firmware keeps running. Re-initialising the controller recovers it.
void displayReinit() {
  tft.setBrightness(0);
  tft.reinitPanelOnly();
  tft.setRotation(gState.settings.rotation);
  tft.setBrightness(0);
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
