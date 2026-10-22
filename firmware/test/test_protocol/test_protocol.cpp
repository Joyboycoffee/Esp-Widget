#include <unity.h>

#include "espw_protocol.h"
#include "vectors_generated.h"

using namespace espw;

void setUp(void) {}
void tearDown(void) {}

static uint8_t hexNibble(char c) {
  if (c >= '0' && c <= '9') return static_cast<uint8_t>(c - '0');
  if (c >= 'a' && c <= 'f') return static_cast<uint8_t>(c - 'a' + 10);
  return static_cast<uint8_t>(c - 'A' + 10);
}

static void test_crc_reference() {
  const char* text = "123456789";
  TEST_ASSERT_EQUAL_HEX16(0x29B1, crc16(reinterpret_cast<const uint8_t*>(text), 9));
}

static void test_decode_known_frames() {
  uint8_t type = 0;
  uint16_t seq = 0;
  const uint8_t* payload = nullptr;
  uint16_t len = 0;

  TEST_ASSERT_TRUE(decodeFrame(espw_test::frame_0, sizeof(espw_test::frame_0), type, seq, payload, len));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(MsgType::Ping), type);
  TEST_ASSERT_EQUAL_UINT16(0x1234, seq);
  TEST_ASSERT_EQUAL_UINT16(0, len);

  TEST_ASSERT_TRUE(decodeFrame(espw_test::frame_1, sizeof(espw_test::frame_1), type, seq, payload, len));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(MsgType::Time), type);
  TEST_ASSERT_EQUAL_UINT16(8, len);
  TimePayload time{};
  memcpy(&time, payload, sizeof(time));
  TEST_ASSERT_EQUAL_HEX32(0x66F0A2B0, time.epoch);
  TEST_ASSERT_EQUAL_INT16(330, time.tz_offset_min);

  TEST_ASSERT_TRUE(decodeFrame(espw_test::frame_3, sizeof(espw_test::frame_3), type, seq, payload, len));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(MsgType::Hello), type);
  Hello hello{};
  memcpy(&hello, payload, sizeof(hello));
  TEST_ASSERT_EQUAL_STRING("ESP-WIDGET-A1B2", hello.name);
  TEST_ASSERT_EQUAL_UINT16(240, hello.screen_w);
}

static void test_rejects_corruption_and_truncation() {
  uint8_t bad[sizeof(espw_test::frame_1)];
  memcpy(bad, espw_test::frame_1, sizeof(bad));
  bad[sizeof(bad) - 1] ^= 0x01;
  uint8_t type;
  uint16_t seq;
  const uint8_t* payload;
  uint16_t len;
  TEST_ASSERT_FALSE(decodeFrame(bad, sizeof(bad), type, seq, payload, len));
  TEST_ASSERT_FALSE(decodeFrame(espw_test::frame_1, sizeof(espw_test::frame_1) - 1, type, seq, payload, len));
}

static void test_reassembly_in_and_out_of_order() {
  Reassembler r;
  bool complete = false;
  for (uint8_t i = 0; i < espw_test::time_chunk_count; ++i) {
    complete = r.feed(espw_test::time_chunks[i], espw_test::time_chunk_lengths[i]);
  }
  TEST_ASSERT_TRUE(complete);
  TEST_ASSERT_TRUE(r.complete());
  TEST_ASSERT_EQUAL_UINT(sizeof(espw_test::time_frame), r.length());
  TEST_ASSERT_EQUAL_HEX8_ARRAY(espw_test::time_frame, r.data(), sizeof(espw_test::time_frame));

  Reassembler out;
  // Feed all but first, which must reset; then feed the correct ordered set.
  TEST_ASSERT_FALSE(out.feed(espw_test::time_chunks[1], espw_test::time_chunk_lengths[1]));
  complete = false;
  for (uint8_t i = 0; i < espw_test::time_chunk_count; ++i) {
    complete = out.feed(espw_test::time_chunks[i], espw_test::time_chunk_lengths[i]);
  }
  TEST_ASSERT_TRUE(complete);
}

static void test_duplicate_chunk_is_ignored() {
  Reassembler r;
  TEST_ASSERT_FALSE(r.feed(espw_test::time_chunks[0], espw_test::time_chunk_lengths[0]));
  TEST_ASSERT_FALSE(r.feed(espw_test::time_chunks[0], espw_test::time_chunk_lengths[0]));
  bool complete = false;
  for (uint8_t i = 1; i < espw_test::time_chunk_count; ++i) {
    complete = r.feed(espw_test::time_chunks[i], espw_test::time_chunk_lengths[i]);
  }
  TEST_ASSERT_TRUE(complete);
  TEST_ASSERT_EQUAL_UINT(sizeof(espw_test::time_frame), r.length());
}

static void test_encode_roundtrip() {
  TimePayload t{};
  t.epoch = 0x01020304;
  t.tz_offset_min = -300;
  uint8_t buf[kMaxFrame];
  const size_t n = encodeFrame(static_cast<uint8_t>(MsgType::Time), 77, &t, sizeof(t), buf, sizeof(buf));
  TEST_ASSERT_EQUAL_UINT(kHeaderSize + sizeof(t) + kCrcSize, n);
  uint8_t type;
  uint16_t seq;
  const uint8_t* payload;
  uint16_t len;
  TEST_ASSERT_TRUE(decodeFrame(buf, n, type, seq, payload, len));
  TEST_ASSERT_EQUAL_UINT16(77, seq);
  TEST_ASSERT_EQUAL_UINT(sizeof(t), len);
  TimePayload out{};
  memcpy(&out, payload, sizeof(out));
  TEST_ASSERT_EQUAL_HEX32(t.epoch, out.epoch);
  TEST_ASSERT_EQUAL_INT16(t.tz_offset_min, out.tz_offset_min);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_crc_reference);
  RUN_TEST(test_decode_known_frames);
  RUN_TEST(test_rejects_corruption_and_truncation);
  RUN_TEST(test_reassembly_in_and_out_of_order);
  RUN_TEST(test_duplicate_chunk_is_ignored);
  RUN_TEST(test_encode_roundtrip);
  return UNITY_END();
}
