// Implementation of the §1.3/§1.4 wire codec. See board_protocol.h for the
// contract and the Kotlin reference (BoardWireCodec.kt). Every byte position and
// every malformed-frame branch mirrors that reference one-to-one.

#include "board_protocol.h"

namespace board_protocol {

namespace {

// board → mobile tags (§1.3)
constexpr uint8_t kTagBoardSnapshot = 0x01;
constexpr uint8_t kTagSquareEvent = 0x02;
constexpr uint8_t kTagButtonEvent = 0x03;
constexpr uint8_t kTagDeviceStatus = 0x04;

// mobile → board tags (§1.4)
constexpr uint8_t kTagSetMode = 0x81;
constexpr uint8_t kTagRequestSnapshot = 0x82;
constexpr uint8_t kTagRequestStatus = 0x83;

// SQUARE_EVENT bit layout (§1.3)
constexpr uint8_t kEventLift = 0x00;
constexpr uint8_t kEventPlace = 0x01;
constexpr uint8_t kSquareMask = 0x3F;
constexpr uint8_t kEventShift = 6;

// payload byte values (§1.3/§1.4)
constexpr uint8_t kButtonWhite = 0x00;
constexpr uint8_t kButtonBlack = 0x01;
constexpr uint8_t kModeGame = 0x00;
constexpr uint8_t kModeDiagnostic = 0x01;

// frame sizes (§1.3/§1.4)
constexpr uint8_t kSnapshotFrameSize = 9;       // 1 tag + 8 occupancy bytes
constexpr uint8_t kDeviceStatusFrameSize = 9;   // 1 tag + battery + 3 version + 4 uptime
constexpr uint8_t kSinglePayloadFrameSize = 2;  // 1 tag + 1 payload byte
constexpr uint8_t kTagOnlyFrameSize = 1;

DecodedCommand malformedCommand() {
    DecodedCommand c{};
    c.kind = CommandKind::Malformed;
    return c;
}

DecodedEvent malformedEvent() {
    DecodedEvent e{};
    e.kind = EventKind::Malformed;
    return e;
}

}  // namespace

Frame encodeSnapshot(uint64_t occupancy) {
    Frame f{};
    f.bytes[0] = kTagBoardSnapshot;
    for (int i = 0; i < 8; ++i) {
        f.bytes[1 + i] = static_cast<uint8_t>((occupancy >> (i * 8)) & 0xFF);
    }
    f.len = kSnapshotFrameSize;
    return f;
}

Frame encodeSquareEvent(uint8_t square, SquareEventType type) {
    const uint8_t eventBits = (type == SquareEventType::Lift) ? kEventLift : kEventPlace;
    Frame f{};
    f.bytes[0] = kTagSquareEvent;
    f.bytes[1] = static_cast<uint8_t>((eventBits << kEventShift) | (square & kSquareMask));
    f.len = kSinglePayloadFrameSize;
    return f;
}

Frame encodeButtonEvent(Button button) {
    Frame f{};
    f.bytes[0] = kTagButtonEvent;
    f.bytes[1] = (button == Button::White) ? kButtonWhite : kButtonBlack;
    f.len = kSinglePayloadFrameSize;
    return f;
}

Frame encodeDeviceStatus(uint8_t batteryPct, uint8_t major, uint8_t minor, uint8_t patch, uint32_t uptimeSeconds) {
    Frame f{};
    f.bytes[0] = kTagDeviceStatus;
    f.bytes[1] = batteryPct;
    f.bytes[2] = major;
    f.bytes[3] = minor;
    f.bytes[4] = patch;
    f.bytes[5] = static_cast<uint8_t>(uptimeSeconds & 0xFF);
    f.bytes[6] = static_cast<uint8_t>((uptimeSeconds >> 8) & 0xFF);
    f.bytes[7] = static_cast<uint8_t>((uptimeSeconds >> 16) & 0xFF);
    f.bytes[8] = static_cast<uint8_t>((uptimeSeconds >> 24) & 0xFF);
    f.len = kDeviceStatusFrameSize;
    return f;
}

size_t deriveSquareEvents(uint64_t prevOccupancy, uint64_t nextOccupancy, Frame* out, size_t outCap) {
    const uint64_t lifted = prevOccupancy & ~nextOccupancy;
    const uint64_t placed = nextOccupancy & ~prevOccupancy;
    size_t count = 0;
    for (uint8_t sq = 0; sq < 64; ++sq) {
        const uint64_t mask = 1ULL << sq;
        SquareEventType type;
        if (lifted & mask) {
            type = SquareEventType::Lift;
        } else if (placed & mask) {
            type = SquareEventType::Place;
        } else {
            continue;
        }
        if (count < outCap) {
            out[count] = encodeSquareEvent(sq, type);
        }
        ++count;
    }
    return count;
}

DecodedCommand decodeCommand(const uint8_t* bytes, size_t len) {
    if (len == 0) return malformedCommand();
    const uint8_t tag = bytes[0];
    if (tag == kTagSetMode) {
        if (len != kSinglePayloadFrameSize) return malformedCommand();
        DecodedCommand c{};
        c.kind = CommandKind::SetMode;
        if (bytes[1] == kModeGame) {
            c.mode = BoardMode::Game;
        } else if (bytes[1] == kModeDiagnostic) {
            c.mode = BoardMode::Diagnostic;
        } else {
            return malformedCommand();
        }
        return c;
    }
    if (tag == kTagRequestSnapshot) {
        if (len != kTagOnlyFrameSize) return malformedCommand();
        DecodedCommand c{};
        c.kind = CommandKind::RequestSnapshot;
        return c;
    }
    if (tag == kTagRequestStatus) {
        if (len != kTagOnlyFrameSize) return malformedCommand();
        DecodedCommand c{};
        c.kind = CommandKind::RequestStatus;
        return c;
    }
    // Unknown / reserved tag (0x84–0x9F included).
    return malformedCommand();
}

DecodedEvent decodeEvent(const uint8_t* bytes, size_t len) {
    if (len == 0) return malformedEvent();
    const uint8_t tag = bytes[0];
    if (tag == kTagBoardSnapshot) {
        if (len != kSnapshotFrameSize) return malformedEvent();
        uint64_t occupancy = 0;
        for (int i = 0; i < 8; ++i) {
            occupancy |= static_cast<uint64_t>(bytes[1 + i]) << (i * 8);
        }
        DecodedEvent e{};
        e.kind = EventKind::BoardSnapshot;
        e.occupancy = occupancy;
        return e;
    }
    if (tag == kTagSquareEvent) {
        if (len != kSinglePayloadFrameSize) return malformedEvent();
        const uint8_t payload = bytes[1];
        const uint8_t eventBits = payload >> kEventShift;
        SquareEventType type;
        if (eventBits == kEventLift) {
            type = SquareEventType::Lift;
        } else if (eventBits == kEventPlace) {
            type = SquareEventType::Place;
        } else {
            return malformedEvent();  // reserved event bits 10/11
        }
        DecodedEvent e{};
        e.kind = EventKind::SquareEvent;
        e.square = static_cast<uint8_t>(payload & kSquareMask);
        e.squareType = type;
        return e;
    }
    if (tag == kTagButtonEvent) {
        if (len != kSinglePayloadFrameSize) return malformedEvent();
        const uint8_t payload = bytes[1];
        Button button;
        if (payload == kButtonWhite) {
            button = Button::White;
        } else if (payload == kButtonBlack) {
            button = Button::Black;
        } else {
            return malformedEvent();
        }
        DecodedEvent e{};
        e.kind = EventKind::ButtonEvent;
        e.button = button;
        return e;
    }
    if (tag == kTagDeviceStatus) {
        if (len != kDeviceStatusFrameSize) return malformedEvent();
        uint32_t uptime = 0;
        for (int i = 0; i < 4; ++i) {
            uptime |= static_cast<uint32_t>(bytes[5 + i]) << (i * 8);
        }
        DecodedEvent e{};
        e.kind = EventKind::DeviceStatus;
        e.batteryPct = bytes[1];
        e.major = bytes[2];
        e.minor = bytes[3];
        e.patch = bytes[4];
        e.uptimeSeconds = uptime;
        return e;
    }
    return malformedEvent();  // unknown event tag
}

}  // namespace board_protocol
