// Implementation of the reed-matrix debounce state machine. The per-square loop
// body is byte-for-byte the same logic as firmware/src/main.cpp's debounce block
// (the hardware-verified diagnostic firmware); see debounce.h for the contract.

#include "debounce.h"

namespace debounce {

void init(Debouncer& d, uint64_t seedRaw) {
    for (int i = 0; i < 64; ++i) {
        d.agree[i] = 0;
    }
    d.stable = 0;
    d.rawPrev = seedRaw;
}

uint64_t step(Debouncer& d, uint64_t raw) {
    for (int i = 0; i < 64; ++i) {
        const uint64_t bit = (raw >> i) & 1ULL;
        const uint64_t prevBit = (d.rawPrev >> i) & 1ULL;
        if (bit == prevBit) {
            if (d.agree[i] < kStableScans) ++d.agree[i];
            if (d.agree[i] == kStableScans) {  // commit debounced bit
                if (bit) {
                    d.stable |= (1ULL << i);
                } else {
                    d.stable &= ~(1ULL << i);
                }
            }
        } else {
            d.agree[i] = 0;  // bounce/noise → restart
        }
    }
    d.rawPrev = raw;
    return d.stable;
}

}  // namespace debounce
