// Smart Chessboard — §1.3/§1.4 BLE wire codec (pure, freestanding C++).
//
// Byte-for-byte twin of the mobile reference
// SmartChessboard/.../data/board/protocol/BoardWireCodec.kt, locked to
// docs/reference/contract-surfaces.md §1. This unit has NO ESP-IDF / Arduino /
// GPIO dependency (only <cstdint>/<cstddef>) so it compiles unchanged into both
// the ESP32 device build and the `native` host test build, where it is asserted
// against the same golden vectors as BoardWireCodecTest.kt.
//
// Snapshot bit packing (§1.3, clarified 2026-06-16): byte `i` bit `j` (LSB-first)
// = square `i*8 + j`, i.e. snapshot byte i = (occupancy >> (i*8)) & 0xFF. Square
// index is `file + 8*rank` (a1 = 0 … h8 = 63). DEVICE_STATUS uptime is an
// UNSIGNED 32-bit little-endian field.
//
// Runtime usage on the firmware is asymmetric: the board ENCODES the four
// board→mobile events and DECODES the three mobile→board commands. decodeEvent()
// is provided for host-test symmetry and completeness (so the malformed-event
// vectors in the contract can be asserted, and so the lib is a full twin of the
// Kotlin codec); the device never calls it at runtime and the linker's
// --gc-sections drops it from the image.

#pragma once

#include <cstddef>
#include <cstdint>

namespace board_protocol {

// Largest frame on the wire: BOARD_SNAPSHOT and DEVICE_STATUS are 9 bytes.
constexpr size_t kMaxFrameLen = 9;

// Fixed-capacity wire frame: the first `len` bytes of `bytes` are valid.
struct Frame {
    uint8_t bytes[kMaxFrameLen];
    uint8_t len;
};

// SQUARE_EVENT high-2-bit event code (§1.3): 00 = lift, 01 = place; 10/11 reserved.
enum class SquareEventType : uint8_t { Lift = 0, Place = 1 };

// BUTTON_EVENT payload (§1.3): 0x00 white, 0x01 black.
enum class Button : uint8_t { White = 0, Black = 1 };

// SET_MODE payload (§1.4): 0x00 game, 0x01 diagnostic.
enum class BoardMode : uint8_t { Game = 0, Diagnostic = 1 };

// ---- Encoders: board → mobile (§1.3). Used by the firmware at runtime. ----

// BOARD_SNAPSHOT: 0x01 + 8 occupancy bytes (byte i = (occupancy >> i*8) & 0xFF).
Frame encodeSnapshot(uint64_t occupancy);

// SQUARE_EVENT: 0x02 + ((eventBits << 6) | square). `square` must be 0..63.
Frame encodeSquareEvent(uint8_t square, SquareEventType type);

// BUTTON_EVENT: 0x03 + (0x00 white / 0x01 black).
Frame encodeButtonEvent(Button button);

// DEVICE_STATUS: 0x04 + battery + major + minor + patch + uptime (u32 LE).
Frame encodeDeviceStatus(uint8_t batteryPct, uint8_t major, uint8_t minor, uint8_t patch, uint32_t uptimeSeconds);

// Derive one SQUARE_EVENT per changed square between two debounced occupancy
// bitmaps (§1.3 / plan Critical Details): lifted = prev & ~next → LIFT;
// placed = next & ~prev → PLACE. One frame per changed square, ascending square
// index — NEVER coalesced (the lift on a capture destination is the discriminator
// SequenceInterpreter relies on). Writes up to `outCap` frames into `out`;
// returns the number written (at most 64; truncates silently if outCap < changes).
size_t deriveSquareEvents(uint64_t prevOccupancy, uint64_t nextOccupancy, Frame* out, size_t outCap);

// ---- Command decoder: mobile → board (§1.4). Used by the firmware at runtime. ----

// Outcome kind of decodeCommand(). `Malformed` is the catch-all for empty,
// unknown/reserved tag (0x84–0x9F), wrong length, out-of-range payload, or a
// stray payload on a tag-only command — decoding is TOTAL and never aborts.
enum class CommandKind : uint8_t { SetMode, RequestSnapshot, RequestStatus, Malformed };

struct DecodedCommand {
    CommandKind kind;
    BoardMode mode;  // meaningful only when kind == SetMode
};

// Total decode of a mobile_command write. Never aborts on hostile input.
DecodedCommand decodeCommand(const uint8_t* bytes, size_t len);

// ---- Event decoder: board → mobile (§1.3). Host-test symmetry only. ----

enum class EventKind : uint8_t { BoardSnapshot, SquareEvent, ButtonEvent, DeviceStatus, Malformed };

struct DecodedEvent {
    EventKind kind;
    uint64_t occupancy;          // BoardSnapshot
    uint8_t square;              // SquareEvent
    SquareEventType squareType;  // SquareEvent
    Button button;               // ButtonEvent
    uint8_t batteryPct;          // DeviceStatus
    uint8_t major;               // DeviceStatus
    uint8_t minor;               // DeviceStatus
    uint8_t patch;               // DeviceStatus
    uint32_t uptimeSeconds;      // DeviceStatus
};

// Total decode of a board_event frame. Mirrors BoardWireCodec.decodeEvent so the
// contract's malformed-event vectors can be asserted on the host. Never aborts.
DecodedEvent decodeEvent(const uint8_t* bytes, size_t len);

}  // namespace board_protocol
