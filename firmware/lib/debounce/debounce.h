// Smart Chessboard — reed-matrix debounce state machine (pure, freestanding C++).
//
// A 1:1 extraction of the per-square agreement debounce that runs in the
// hardware-verified diagnostic firmware (firmware/src/main.cpp, 2026-05-28). No
// timing, no GPIO — the caller owns the scan cadence and feeds raw 64-bit
// occupancy bitmaps (bit `i` = square `i`, index = file + 8*rank). Behaviour MUST
// stay identical to the original loop; the native test is the regression guard.
//
// Rule: a per-square counter increments while a raw bit AGREES with the previous
// raw scan and resets to 0 on any disagreement; once it reaches kStableScans the
// bit is committed to the debounced `stable` bitmap. (main.cpp Phase 3 will adopt
// this unit; in Phase 1 it is test-only and main.cpp keeps its own copy.)

#pragma once

#include <cstdint>

namespace debounce {

// Consecutive agreeing scans required to commit a bit (~80 ms at the ~50 Hz scan
// cadence). Matches kStableScans in firmware/src/main.cpp.
constexpr uint8_t kStableScans = 4;

// Debounce state for all 64 squares. Treat fields as opaque; drive via the
// functions below. `stable` is the committed (debounced) occupancy bitmap.
struct Debouncer {
    uint8_t agree[64];  // per-square consecutive-agreement counter
    uint64_t stable;    // committed debounced occupancy
    uint64_t rawPrev;   // previous raw scan (for the agreement comparison)
};

// Seed with the first raw scan, mirroring main.cpp's pre-loop
// `rawPrev = scan_matrix()`: counters start at 0 and `stable` starts at 0
// regardless of the seed (a board that boots occupied converges after the
// debounce window). Call once before the first step().
void init(Debouncer& d, uint64_t seedRaw);

// Feed one raw scan; update the per-square counters and the committed `stable`
// bitmap per the kStableScans agreement rule, then return the new `stable`.
uint64_t step(Debouncer& d, uint64_t raw);

}  // namespace debounce
