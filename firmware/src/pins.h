#pragma once
#include "driver/gpio.h"

// ===========================================================================
//  Smart Chessboard - reed-switch 8x8 matrix pin map  (EDIT TO MATCH HARDWARE)
// ===========================================================================
// Rows are OUTPUTS, driven LOW one at a time. Columns are INPUTS with the
// internal pull-up enabled: idle = HIGH, a closed reed switch reads LOW.
//
// Square index convention (matches docs/reference/contract-surfaces.md S1.3):
//   index = file + 8 * rank,  file a..h = 0..7,  rank 1..8 = 0..7
//   => a1 = 0, h1 = 7, a8 = 56, h8 = 63
// Default mapping in main.cpp: ROW index = rank, COL index = file.
// Visual per-board header maps (DevKitC V4 + DevKit V1): see ../PINOUT.md
//
// ---------------------------------------------------------------------------
//  CURRENT MAPPING = existing prototype wiring on the DevKit V1 (reused from an
//  earlier project; two physically-consecutive header blocks). This is a
//  bringup/test mapping, NOT the hazard-free design.
//
//  The file-g column was moved OFF GPIO2 (DOIT V1 onboard LED, which made that
//  column read stuck-closed) to GPIO21 = D21 (clean pin). Remaining watch item:
//    * GPIO12 (ROW6) - flash-voltage strapping (must be LOW at boot). Fine as an
//      output in practice, but if the board ever boot-loops, suspect this pin.
//  Strapping pins used as pull-up inputs (GPIO5, GPIO15) boot in a safe state
//  and are tolerable. Full hazard-free target map: ../PINOUT.md / ../WIRING.md.
// ---------------------------------------------------------------------------
//
// NOTE: row/col index <-> rank/file orientation is calibratable. After flashing,
// bridge one row+col and see which square lights; if it's mirrored/rotated,
// adjust square_index() in main.cpp rather than rewiring.

static constexpr int kNumRows = 8;
static constexpr int kNumCols = 8;

// ROW index 0..7 == rank 1..8  (outputs). DevKit V1 LEFT header, top->bottom.
static constexpr gpio_num_t kRowPins[kNumRows] = {
    GPIO_NUM_32,  // ROW0  (D32)
    GPIO_NUM_33,  // ROW1  (D33)
    GPIO_NUM_25,  // ROW2  (D25)
    GPIO_NUM_26,  // ROW3  (D26)
    GPIO_NUM_27,  // ROW4  (D27)
    GPIO_NUM_14,  // ROW5  (D14)
    GPIO_NUM_12,  // ROW6  (D12)  *flash-voltage strapping - see TODO
    GPIO_NUM_13,  // ROW7  (D13)
};

// COL index 0..7 == file a..h  (inputs, internal pull-up). V1 RIGHT header, top->bottom.
static constexpr gpio_num_t kColPins[kNumCols] = {
    GPIO_NUM_19,  // COL0  (D19)
    GPIO_NUM_18,  // COL1  (D18)
    GPIO_NUM_5,   // COL2  (D5)   *mild strapping (boots OK as pulled-up input)
    GPIO_NUM_17,  // COL3  (TX2)
    GPIO_NUM_16,  // COL4  (RX2)
    GPIO_NUM_4,   // COL5  (D4)   *weak boot pull-down (pull-up overrides) - OK
    GPIO_NUM_21,  // COL6  (D21)  clean GPIO - replaced GPIO2/D2 (onboard LED)
    GPIO_NUM_15,  // COL7  (D15)  *strapping (boots HIGH = matches idle pull-up)
};

// ---------------------------------------------------------------------------
//  Confirmation buttons (F-03 / FR-FW-007) - ADDITIVE, separate from the matrix.
//  Two momentary push-buttons wired to GND, read as inputs with the internal
//  pull-up: idle = HIGH, pressed = LOW. GPIO22/23 are bonded out on the
//  WROOM-32, are NOT used by the matrix above, and are NOT strapping pins.
//  White confirms a white move/turn, black the black side - bare events only,
//  the firmware does no turn validation (the mobile re-derives whose turn it is).
// ---------------------------------------------------------------------------
static constexpr gpio_num_t kButtonWhitePin = GPIO_NUM_22;  // white -> BUTTON_EVENT 0x00
static constexpr gpio_num_t kButtonBlackPin = GPIO_NUM_23;  // black -> BUTTON_EVENT 0x01
