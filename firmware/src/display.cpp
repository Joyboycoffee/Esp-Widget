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
void displayReinit(bool hard) {
  if (!hard) {
    displayHeal();
    return;
  }
  tft.reinitPanelHard();
  tft.setRotation(gState.settings.rotation);
}

// Re-sends the ST7789 set-up as plain commands over the already-running SPI bus
// (no bus re-init, no reset, no clear, no backlight change). Writing the same
// values to a healthy panel changes nothing on screen; a panel that lost its
// settings in a supply dip gets them back. The caller repaints afterwards.
void displayHeal() {
  struct Cmd { uint8_t cmd; uint8_t len; uint8_t data[14]; };
  static const Cmd kSetup[] = {
      {0xB7, 1, {0x35}},                    // GCTRL
      {0xBB, 1, {0x28}},                    // VCOMS
      {0xC0, 1, {0x0C}},                    // LCMCTRL
      {0xC2, 2, {0x01, 0xFF}},              // VDVVRHEN
      {0xC3, 1, {0x10}},                    // VRHS
      {0xC4, 1, {0x20}},                    // VDVSET
      {0xD0, 2, {0xA4, 0xA1}},              // PWCTRL1
      {0xB0, 2, {0x00, 0xC0}},              // RAMCTRL
      {0xE0, 14, {0xD0, 0x00, 0x02, 0x07, 0x0A, 0x28, 0x32, 0x44, 0x42, 0x06, 0x0E, 0x12, 0x14, 0x17}},
      {0xE1, 14, {0xD0, 0x00, 0x02, 0x07, 0x0A, 0x28, 0x31, 0x54, 0x47, 0x0E, 0x1C, 0x17, 0x1B, 0x1E}},
      {0x3A, 1, {0x55}},                    // COLMOD 16-bit
      {0x21, 0, {}},                        // INVON (panel config: invert = true)
      {0x13, 0, {}},                        // NORON
      {0x38, 0, {}},                        // IDMOFF
  };
  tft.startWrite();
  tft.writeCommand(0x11);  // SLPOUT (no effect if already awake)
  tft.endWrite();
  delay(10);
  tft.startWrite();
  for (const auto& c : kSetup) {
    tft.writeCommand(c.cmd);
    for (uint8_t i = 0; i < c.len; ++i) tft.writeData(c.data[i]);
  }
  tft.writeCommand(0x29);  // DISPON
  tft.endWrite();
  tft.setRotation(gState.settings.rotation);  // re-sends MADCTL (RGB order + rotation)
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
