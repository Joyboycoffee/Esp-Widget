#pragma once
#include <Arduino.h>

// The Keychain repository contains three different pin tables. Profile 0 uses
// the values the working firmware actually compiles with (config.h and
// display_setup.h). Profiles 1 and 2 match the stale README / wiring guide.
#ifndef ESPW_PIN_PROFILE
#define ESPW_PIN_PROFILE 0
#endif

#if ESPW_PIN_PROFILE == 0
#define PIN_TFT_SCL 6
#define PIN_TFT_SDA 10
#define PIN_TFT_RES 5
#define PIN_TFT_DC 3
#define PIN_TFT_BL 4
#define PIN_TOUCH 1
#define PIN_DEBUG_LED 8
#elif ESPW_PIN_PROFILE == 1
#define PIN_TFT_SCL 6
#define PIN_TFT_SDA 10
#define PIN_TFT_RES 5
#define PIN_TFT_DC 3
#define PIN_TFT_BL 2
#define PIN_TOUCH 4
#define PIN_DEBUG_LED 8
#elif ESPW_PIN_PROFILE == 2
#define PIN_TFT_SCL 8
#define PIN_TFT_SDA 10
#define PIN_TFT_RES 5
#define PIN_TFT_DC 3
#define PIN_TFT_BL 2
#define PIN_TOUCH 1
#define PIN_DEBUG_LED -1
#else
#error "Unknown ESPW_PIN_PROFILE"
#endif

#define PIN_TFT_CS -1
#define PIN_BAT_ADC 0
#define PIN_TP_BLUE 20
#define PIN_TP_RED 21

#define TOUCH_ACTIVE_LEVEL HIGH
#define SCREEN_W 240
#define SCREEN_H 240

#define ESPW_BLE_NAME_PREFIX "ESP-WIDGET"
#define ESPW_SERVICE_UUID "8f1d0001-6a4d-4c1e-9a1e-73e2f4b1c001"
#define ESPW_RX_UUID "8f1d0002-6a4d-4c1e-9a1e-73e2f4b1c001"
#define ESPW_TX_UUID "8f1d0003-6a4d-4c1e-9a1e-73e2f4b1c001"
