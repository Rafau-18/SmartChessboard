// Golden-vector tests for the §1.3/§1.4 wire codec (firmware/lib/board_protocol).
//
// Every literal frame here is the SAME byte vector asserted in the mobile
// reference test BoardWireCodecTest.kt — this file is the firmware half of the
// cross-language oracle that keeps the device a byte-for-byte twin of the
// emulator's stream. Each valid frame is asserted in BOTH directions
// independently (encoder output == literal; literal decoded == typed value), and
// every malformed/reserved frame is asserted to decode to Malformed (decoding is
// total, never aborts).

#include <unity.h>

#include <initializer_list>

#include "board_protocol.h"

namespace bp = board_protocol;

void setUp(void) {}
void tearDown(void) {}

// Helpers take std::initializer_list so call sites read as plain byte vectors
// ({0x02, 0x00}) without relying on the non-standard C99 compound-literal
// extension. An initializer_list's storage is contiguous and lives for the full
// expression, so .begin() is a valid pointer for the duration of each call.

static void assertFrame(std::initializer_list<uint8_t> expected, const bp::Frame& f) {
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(expected.size()), f.len);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected.begin(), f.bytes, expected.size());
}

static bp::DecodedEvent decEv(std::initializer_list<uint8_t> b) {
    return bp::decodeEvent(b.begin(), b.size());
}

static bp::DecodedCommand decCmd(std::initializer_list<uint8_t> b) {
    return bp::decodeCommand(b.begin(), b.size());
}

static void assertEventMalformed(std::initializer_list<uint8_t> b) {
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::Malformed), static_cast<int>(decEv(b).kind));
}

static void assertCommandMalformed(std::initializer_list<uint8_t> b) {
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::CommandKind::Malformed), static_cast<int>(decCmd(b).kind));
}

// --- SQUARE_EVENT (§1.3, tag 0x02): square low 6 bits, event high 2 bits (00 lift, 01 place) ---

static void test_encode_square_event_golden(void) {
    // a1 = square 0; h8 = square 63 (corners). e2 = 12, e4 = 28 (file e=4: 4+8*rank).
    assertFrame({0x02, 0x00}, bp::encodeSquareEvent(0, bp::SquareEventType::Lift));
    assertFrame({0x02, 0x40}, bp::encodeSquareEvent(0, bp::SquareEventType::Place));
    assertFrame({0x02, 0x3F}, bp::encodeSquareEvent(63, bp::SquareEventType::Lift));
    assertFrame({0x02, 0x7F}, bp::encodeSquareEvent(63, bp::SquareEventType::Place));
    assertFrame({0x02, 0x0C}, bp::encodeSquareEvent(12, bp::SquareEventType::Lift));
    assertFrame({0x02, 0x5C}, bp::encodeSquareEvent(28, bp::SquareEventType::Place));
}

static void assertDecodedSquare(std::initializer_list<uint8_t> b, uint8_t square, bp::SquareEventType type) {
    const bp::DecodedEvent e = decEv(b);
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::SquareEvent), static_cast<int>(e.kind));
    TEST_ASSERT_EQUAL_UINT8(square, e.square);
    TEST_ASSERT_EQUAL_INT(static_cast<int>(type), static_cast<int>(e.squareType));
}

static void test_decode_square_event_golden(void) {
    assertDecodedSquare({0x02, 0x00}, 0, bp::SquareEventType::Lift);
    assertDecodedSquare({0x02, 0x40}, 0, bp::SquareEventType::Place);
    assertDecodedSquare({0x02, 0x3F}, 63, bp::SquareEventType::Lift);
    assertDecodedSquare({0x02, 0x7F}, 63, bp::SquareEventType::Place);
    assertDecodedSquare({0x02, 0x0C}, 12, bp::SquareEventType::Lift);
    assertDecodedSquare({0x02, 0x5C}, 28, bp::SquareEventType::Place);
}

// --- BUTTON_EVENT (§1.3, tag 0x03): 0x00 white, 0x01 black ---

static void test_encode_button_event_golden(void) {
    assertFrame({0x03, 0x00}, bp::encodeButtonEvent(bp::Button::White));
    assertFrame({0x03, 0x01}, bp::encodeButtonEvent(bp::Button::Black));
}

static void test_decode_button_event_golden(void) {
    const bp::DecodedEvent w = decEv({0x03, 0x00});
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::ButtonEvent), static_cast<int>(w.kind));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::Button::White), static_cast<int>(w.button));
    const bp::DecodedEvent b = decEv({0x03, 0x01});
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::ButtonEvent), static_cast<int>(b.kind));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::Button::Black), static_cast<int>(b.button));
}

// --- BOARD_SNAPSHOT (§1.3, tag 0x01): 8 occupancy bytes, byte i bit j (LSB-first) = square i*8+j ---

// Ranks 1,2 (squares 0–15) and ranks 7,8 (squares 48–63) — chess start occupancy.
static const uint64_t kStartOccupancy = 0xFFFFULL | (0xFFFFULL << 48);
static const uint64_t kAllOccupied = ~0ULL;

static void test_encode_snapshot_golden(void) {
    assertFrame({0x01, 0, 0, 0, 0, 0, 0, 0, 0}, bp::encodeSnapshot(0));
    assertFrame({0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, bp::encodeSnapshot(kAllOccupied));
    assertFrame({0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF}, bp::encodeSnapshot(kStartOccupancy));
    // a2 = square 8 → byte 1, bit 0 (pins the byte index).
    assertFrame({0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}, bp::encodeSnapshot(1ULL << 8));
    // h1 = square 7 → byte 0, bit 7 (pins LSB-first bit ordering within a byte).
    assertFrame({0x01, 0x80, 0, 0, 0, 0, 0, 0, 0}, bp::encodeSnapshot(1ULL << 7));
}

static void assertDecodedSnapshot(std::initializer_list<uint8_t> b, uint64_t occupancy) {
    const bp::DecodedEvent e = decEv(b);
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::BoardSnapshot), static_cast<int>(e.kind));
    TEST_ASSERT_EQUAL_UINT64(occupancy, e.occupancy);
}

static void test_decode_snapshot_golden(void) {
    assertDecodedSnapshot({0x01, 0, 0, 0, 0, 0, 0, 0, 0}, 0);
    assertDecodedSnapshot({0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, kAllOccupied);
    assertDecodedSnapshot({0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF}, kStartOccupancy);
    assertDecodedSnapshot({0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}, 1ULL << 8);
    assertDecodedSnapshot({0x01, 0x80, 0, 0, 0, 0, 0, 0, 0}, 1ULL << 7);
}

// --- DEVICE_STATUS (§1.3, tag 0x04): battery 1B, fw 3B (major/minor/patch), uptime uint32 LE 4B ---

static void test_encode_device_status_golden(void) {
    // battery 100 = 0x64, fw 1.2.3, uptime 0.
    assertFrame({0x04, 0x64, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00}, bp::encodeDeviceStatus(100, 1, 2, 3, 0));
    // uptime 0x04030201 = 67305985 → LE bytes 01 02 03 04 (proves byte order, not value).
    assertFrame({0x04, 0x32, 0x02, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04},
                bp::encodeDeviceStatus(50, 2, 0, 1, 67305985U));
    // uptime 0xFFFFFFFF = 4294967295 (unsigned, NOT -1).
    assertFrame({0x04, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF},
                bp::encodeDeviceStatus(0, 0, 0, 0, 4294967295U));
}

static void assertDecodedStatus(std::initializer_list<uint8_t> b, uint8_t battery, uint8_t major, uint8_t minor,
                                uint8_t patch, uint32_t uptime) {
    const bp::DecodedEvent e = decEv(b);
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::EventKind::DeviceStatus), static_cast<int>(e.kind));
    TEST_ASSERT_EQUAL_UINT8(battery, e.batteryPct);
    TEST_ASSERT_EQUAL_UINT8(major, e.major);
    TEST_ASSERT_EQUAL_UINT8(minor, e.minor);
    TEST_ASSERT_EQUAL_UINT8(patch, e.patch);
    TEST_ASSERT_EQUAL_UINT32(uptime, e.uptimeSeconds);
}

static void test_decode_device_status_golden(void) {
    assertDecodedStatus({0x04, 0x64, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00}, 100, 1, 2, 3, 0);
    assertDecodedStatus({0x04, 0x32, 0x02, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04}, 50, 2, 0, 1, 67305985U);
    // uptime 0xFFFFFFFF must decode to 4294967295, NOT -1 — the unsigned-width guard.
    assertDecodedStatus({0x04, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF}, 0, 0, 0, 0, 4294967295U);
}

// --- Commands mobile → board (§1.4): SET_MODE 0x81, REQUEST_SNAPSHOT 0x82, REQUEST_STATUS 0x83 ---

static void test_decode_command_golden(void) {
    const bp::DecodedCommand g = decCmd({0x81, 0x00});
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::CommandKind::SetMode), static_cast<int>(g.kind));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::BoardMode::Game), static_cast<int>(g.mode));

    const bp::DecodedCommand d = decCmd({0x81, 0x01});
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::CommandKind::SetMode), static_cast<int>(d.kind));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::BoardMode::Diagnostic), static_cast<int>(d.mode));

    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::CommandKind::RequestSnapshot), static_cast<int>(decCmd({0x82}).kind));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(bp::CommandKind::RequestStatus), static_cast<int>(decCmd({0x83}).kind));
}

// --- Malformed frames: decoding is total, never aborts; every bad frame is reported ---

static void test_malformed_events(void) {
    assertEventMalformed({});                                      // empty
    assertEventMalformed({0x05, 0x00});                            // unknown event tag
    assertEventMalformed({0x01, 0xFF});                            // BOARD_SNAPSHOT truncated (2 of 9)
    assertEventMalformed({0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0});       // oversized snapshot (10 bytes)
    assertEventMalformed({0x02});                                  // SQUARE_EVENT missing payload
    assertEventMalformed({0x02, 0x85});                            // SQUARE_EVENT reserved event bits 10
    assertEventMalformed({0x02, 0xC0});                            // SQUARE_EVENT reserved event bits 11
    assertEventMalformed({0x03, 0x02});                            // BUTTON_EVENT out-of-range button
    assertEventMalformed({0x04, 0x64});                            // DEVICE_STATUS truncated
}

static void test_malformed_commands(void) {
    assertCommandMalformed({});            // empty
    assertCommandMalformed({0x84});        // reserved post-MVP tag (0x84–0x9F)
    assertCommandMalformed({0x90});        // reserved post-MVP tag
    assertCommandMalformed({0x81, 0x02});  // SET_MODE out-of-range mode
    assertCommandMalformed({0x81});        // SET_MODE missing payload
    assertCommandMalformed({0x82, 0x00});  // REQUEST_SNAPSHOT with stray payload
    assertCommandMalformed({0x83, 0x00});  // REQUEST_STATUS with stray payload
}

// --- stable-diff → SQUARE_EVENT derivation (§1.3 / Critical Details): one event per changed square, by index ---

static void test_derive_square_events(void) {
    bp::Frame out[4];

    // No change → no events.
    TEST_ASSERT_EQUAL_UINT32(0, bp::deriveSquareEvents(0, 0, out, 4));

    // Lift only: e2 (sq12) removed from a two-square occupancy.
    const uint64_t prev1 = (1ULL << 12) | (1ULL << 13);
    const uint64_t next1 = (1ULL << 13);
    TEST_ASSERT_EQUAL_UINT32(1, bp::deriveSquareEvents(prev1, next1, out, 4));
    assertFrame({0x02, 0x0C}, out[0]);  // sq12 lift

    // Move e2→e4: lift sq12, place sq28 — emitted ascending by index (lift, then place).
    const uint64_t prev2 = (1ULL << 12);
    const uint64_t next2 = (1ULL << 28);
    TEST_ASSERT_EQUAL_UINT32(2, bp::deriveSquareEvents(prev2, next2, out, 4));
    assertFrame({0x02, 0x0C}, out[0]);  // sq12 lift
    assertFrame({0x02, 0x5C}, out[1]);  // sq28 place
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_encode_square_event_golden);
    RUN_TEST(test_decode_square_event_golden);
    RUN_TEST(test_encode_button_event_golden);
    RUN_TEST(test_decode_button_event_golden);
    RUN_TEST(test_encode_snapshot_golden);
    RUN_TEST(test_decode_snapshot_golden);
    RUN_TEST(test_encode_device_status_golden);
    RUN_TEST(test_decode_device_status_golden);
    RUN_TEST(test_decode_command_golden);
    RUN_TEST(test_malformed_events);
    RUN_TEST(test_malformed_commands);
    RUN_TEST(test_derive_square_events);
    return UNITY_END();
}
