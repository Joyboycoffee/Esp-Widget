#pragma once
#include <LovyanGFX.hpp>

#include "board_config.h"

class LGFX_ST7789 : public lgfx::LGFX_Device {
  lgfx::Panel_ST7789 _panel_instance;
  lgfx::Bus_SPI _bus_instance;
  lgfx::Light_PWM _light_instance;

 public:
  LGFX_ST7789() {
    {
      auto cfg = _bus_instance.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 3;
      cfg.freq_write = 40000000;
      cfg.freq_read = 16000000;
      cfg.spi_3wire = false;
      cfg.use_lock = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = PIN_TFT_SCL;
      cfg.pin_mosi = PIN_TFT_SDA;
      cfg.pin_miso = -1;
      cfg.pin_dc = PIN_TFT_DC;
      _bus_instance.config(cfg);
      _panel_instance.setBus(&_bus_instance);
    }
    {
      auto cfg = _panel_instance.config();
      cfg.pin_cs = PIN_TFT_CS;
      cfg.pin_rst = PIN_TFT_RES;
      cfg.pin_busy = -1;
      cfg.panel_width = SCREEN_W;
      cfg.panel_height = SCREEN_H;
      cfg.memory_width = 240;
      cfg.memory_height = 320;
      cfg.offset_x = 0;
      cfg.offset_y = 0;
      cfg.offset_rotation = 0;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits = 1;
      cfg.readable = false;
      cfg.invert = true;
      cfg.rgb_order = false;  // panel is BGR: true gave red<->blue swapped everywhere
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      _panel_instance.config(cfg);
    }
    {
      auto cfg = _light_instance.config();
      cfg.pin_bl = PIN_TFT_BL;
      cfg.invert = false;
      cfg.freq = 5000;
      cfg.pwm_channel = 0;
      _light_instance.config(cfg);
      _panel_instance.setLight(&_light_instance);
    }
    setPanel(&_panel_instance);
  }

  // Re-send the panel set-up. soft = no reset pulse and no screen clear: on a
  // healthy panel nothing visible changes, on one that browned out it restores
  // the settings (the caller then repaints). hard = full reset + clear.
  // The backlight PWM is never touched, so the screen never blinks dark.
  void reinitPanel(bool hard) {
    _panel_instance.setLight(nullptr);
    if (hard) init();
    else init_without_reset(false);
    _panel_instance.setLight(&_light_instance);
  }
};

extern LGFX_ST7789 tft;

void displayBegin();
void displayReinit(bool hard = false);
void displayFadeIn(uint8_t target);
void displayFadeOut(uint8_t from);
void displaySleepHold();
