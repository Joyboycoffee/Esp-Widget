#include "ble.h"

#include <NimBLEDevice.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "app_state.h"
#include "board_config.h"
#include "sync.h"

namespace ble {
namespace {
struct Chunk {
  uint16_t len;
  uint8_t data[512];
};

NimBLEServer* s_server = nullptr;
NimBLECharacteristic* s_tx = nullptr;
NimBLECharacteristic* s_rx = nullptr;
QueueHandle_t s_queue = nullptr;
espw::Reassembler s_reassembler;
char s_name[24];
uint16_t s_connHandle = 0;
bool s_advertising = false;
bool s_connected = false;
uint32_t s_advertiseUntil = 0;
uint32_t s_passkey = 0;
// NimBLE callbacks run on the BLE host task. Sending from there raced with
// sends from loop() (shared sequence/fragment counters) and corrupted frames,
// so callbacks only raise these flags and loop() does the actual work.
volatile bool s_pendingHello = false;
volatile bool s_pendingConnParams = false;

void rebuildName() {
  String mac = NimBLEDevice::getAddress().toString().c_str();
  const size_t n = mac.length();
  const char* tail = n >= 5 ? mac.c_str() + n - 5 : "0000";
  char compact[5] = {0};
  int j = 0;
  for (int i = 0; tail[i] && j < 4; ++i) {
    if (tail[i] != ':') compact[j++] = tail[i];
  }
  snprintf(s_name, sizeof(s_name), "%s-%s", ESPW_BLE_NAME_PREFIX, compact);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    (void)server;
    s_connHandle = info.getConnHandle();
    s_connected = true;
    gState.bleConnected = true;
    gState.lastActivityMs = millis();
    gState.dirty = true;
    s_advertising = false;
    s_advertiseUntil = 0;
    s_pendingConnParams = true;
    Serial.printf("[BLE] connected, handle=%u\n", s_connHandle);
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    (void)server;
    (void)info;
    (void)reason;
    s_connected = false;
    gState.bleConnected = false;
    gState.pairingActive = false;
    gState.lastActivityMs = millis();
    gState.dirty = true;
    Serial.println("[BLE] disconnected");
    if (gState.bleActive) startAdvertising(0);
  }

  uint32_t onPassKeyDisplay() override {
    s_passkey = 100000 + (esp_random() % 900000);
    gState.pairingPasskey = s_passkey;
    gState.pairingActive = true;
    gState.dirty = true;
    Serial.printf("[BLE] pairing passkey %06lu\n", static_cast<unsigned long>(s_passkey));
    return s_passkey;
  }

  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    if (!info.isEncrypted()) {
      Serial.println("[BLE] authentication: link not encrypted yet");
      return;
    }
    if (gState.pairingActive) stateSetToast("Phone paired");
    gState.pairingActive = false;
    gState.dirty = true;
    s_pendingHello = true;
    Serial.println("[BLE] link encrypted and authenticated");
  }
} s_serverCallbacks;

class RxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) override {
    (void)info;
    const NimBLEAttValue value = characteristic->getValue();
    if (value.length() == 0 || value.length() > sizeof(Chunk::data)) return;
    Chunk chunk{};
    chunk.len = value.length();
    memcpy(chunk.data, value.data(), chunk.len);
    if (xQueueSend(s_queue, &chunk, 0) != pdTRUE) {
      Serial.println("[BLE] chunk queue full");
    }
  }
} s_rxCallbacks;

class TxCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic* characteristic, NimBLEConnInfo& info,
                   uint16_t subValue) override {
    (void)characteristic;
    (void)info;
    if (subValue & 0x0001) s_pendingHello = true;
  }
} s_txCallbacks;

}  // namespace

const char* deviceName() { return s_name; }

void begin() {
  s_queue = xQueueCreate(16, sizeof(Chunk));
  NimBLEDevice::init("ESP-WIDGET");
  rebuildName();
  NimBLEDevice::setDeviceName(s_name);
  NimBLEDevice::setMTU(247);
  NimBLEDevice::setPower(ESP_PWR_LVL_P3);
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);

  s_server = NimBLEDevice::createServer();
  s_server->setCallbacks(&s_serverCallbacks);
  s_server->advertiseOnDisconnect(false);

  NimBLEService* service = s_server->createService(ESPW_SERVICE_UUID);
  s_rx = service->createCharacteristic(
      ESPW_RX_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_ENC,
      512);
  s_rx->setCallbacks(&s_rxCallbacks);
  s_tx = service->createCharacteristic(
      ESPW_TX_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::NOTIFY,
      512);
  s_tx->setCallbacks(&s_txCallbacks);

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->enableScanResponse(true);
  adv->setName(s_name);
  adv->addServiceUUID(ESPW_SERVICE_UUID);
  // 250-400 ms: still connects within a second, much cheaper than 100 ms.
  adv->setMinInterval(400);
  adv->setMaxInterval(640);
  gState.bleActive = true;
  startAdvertising(0);
  Serial.printf("[BLE] advertising as %s\n", s_name);
}

void startAdvertising(uint32_t windowMs) {
  if (!gState.bleActive || s_connected) return;
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  if (adv) {
    adv->start();
    s_advertising = true;
    s_advertiseUntil = windowMs ? millis() + windowMs : 0;
  }
}

void stopAdvertising() {
  if (s_advertising) {
    NimBLEDevice::getAdvertising()->stop();
    s_advertising = false;
  }
}

void disconnect() {
  if (s_connected) {
    NimBLEDevice::getServer()->disconnect(s_connHandle);
  }
}

bool connected() { return s_connected; }

void clearBonds() { NimBLEDevice::deleteAllBonds(); }

void send(uint8_t type, const void* payload, uint16_t len) {
  if (!s_connected || !s_tx) return;
  static uint16_t txSeq = 0;
  uint8_t frame[espw::kMaxFrame];
  const size_t frameLen = espw::encodeFrame(type, ++txSeq, payload, len, frame, sizeof(frame));
  if (frameLen == 0) return;
  const uint16_t mtu = s_server ? s_server->getPeerMTU(s_connHandle) : 23;
  size_t chunkPayload = mtu > 30 ? mtu - 3 - espw::kFragHeader : 15;
  if (chunkPayload > 239) chunkPayload = 239;
  const uint8_t parts = espw::fragmentCount(frameLen, chunkPayload);
  if (parts == 0) return;
  static uint8_t frameId = 0;
  ++frameId;
  uint8_t chunk[espw::kFragHeader + 239];
  for (uint8_t part = 0; part < parts; ++part) {
    const size_t offset = part * chunkPayload;
    const size_t n = min(chunkPayload, frameLen - offset);
    espw::writeFragmentHeader(chunk, frameId, part, parts);
    memcpy(chunk + espw::kFragHeader, frame + offset, n);
    s_tx->notify(chunk, espw::kFragHeader + n, s_connHandle);
    delay(2);
  }
}

bool s_lowPower = false;

void setLowPower(bool low) {
  if (s_lowPower == low) return;
  s_lowPower = low;
  s_pendingConnParams = true;
}

void loop() {
  if (s_pendingConnParams && s_connected && s_server) {
    s_pendingConnParams = false;
    if (s_lowPower) {
      // Screen off: 200-300 ms interval, skip up to 2 events, 8 s timeout.
      // The radio then wakes ~4x a second instead of ~25x; messages still
      // arrive within a fraction of a second.
      s_server->updateConnParams(s_connHandle, 160, 240, 2, 800);
    } else {
      s_server->updateConnParams(s_connHandle, 24, 40, 4, 600);
    }
  }
  static uint32_t s_lastStatusPush = 0;
  if (gState.statusDirty && s_connected && !s_pendingHello && millis() - s_lastStatusPush > 500) {
    gState.statusDirty = false;
    s_lastStatusPush = millis();
    espsync::sendStatus();
  }
  if (s_pendingHello && s_connected) {
    s_pendingHello = false;
    espsync::sendHello();
    espsync::sendStatus();
  }
  if (s_advertising && s_advertiseUntil && millis() > s_advertiseUntil && !s_connected) {
    stopAdvertising();
    Serial.println("[BLE] advertising window elapsed");
  }
  if (!s_queue) return;
  Chunk chunk;
  for (int i = 0; i < 8 && xQueueReceive(s_queue, &chunk, 0) == pdTRUE; ++i) {
    if (!s_reassembler.feed(chunk.data, chunk.len)) continue;
    uint8_t type = 0;
    uint16_t seq = 0;
    uint16_t payloadLen = 0;
    const uint8_t* payload = nullptr;
    if (!espw::decodeFrame(s_reassembler.data(), s_reassembler.length(), type, seq,
                           payload, payloadLen)) {
      espsync::sendNack(0, 1);
    } else {
      espsync::handleFrame(type, payload, payloadLen, seq);
    }
    s_reassembler.reset();
  }
}

}  // namespace ble
