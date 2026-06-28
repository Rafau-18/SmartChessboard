#pragma once
#include "driver/gpio.h"

// ===========================================================================
//  Smart Chessboard - reed-switch 8x8 matrix pin map  (EDIT TO MATCH HARDWARE)
// ===========================================================================
// COLUMNS are OUTPUTS, driven LOW one at a time. ROWS are INPUTS with the internal
// pull-up enabled: idle = HIGH, a closed reed switch (its series diode conducting)
// pulls the row LOW. Diodes are mounted cathode-toward-column, which is why the scan
// drives columns and reads rows (see main.cpp scan_matrix()).
//
// Square index convention (matches docs/reference/contract-surfaces.md S1.3):
//   index = file + 8 * rank,  file a..h = 0..7,  rank 1..8 = 0..7
//   => a1 = 0, h1 = 7, a8 = 56, h8 = 63
// main.cpp: ROW index = rank, COL index = file (calibrate square_index() if rotated).
// Visual per-board header maps (DevKitC V4 + DevKit V1): see ../PINOUT.md
//
// ---------------------------------------------------------------------------
//  CURRENT MAPPING = the real bring-up wiring on the DevKit V1, verified against
//  the physical reed matrix (anti-ghosting diodes installed). This is the
//  authoritative map; the ../HARDWARE.md / ../PINOUT.md / ../WIRING.md diagrams
//  (which still describe the older row-drive prototype) are being reconciled to it.
//
//  Watch item: GPIO12 (ROW6) is flash-voltage strapping (must be LOW at boot); it
//  is a pull-up input here, so if the board ever boot-loops, suspect this pin.
//  GPIO5 / GPIO15 (now columns, i.e. outputs) are strapping pins that boot safe.
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

// COL index 0..7 == file a..h  (now OUTPUTS after diode-direction fix in main.cpp).
static constexpr gpio_num_t kColPins[kNumCols] = {
    GPIO_NUM_15,  // COL0 = file a
    GPIO_NUM_4,   // COL1 = file b
    GPIO_NUM_16,  // COL2 = file c
    GPIO_NUM_17,  // COL3 = file d
    GPIO_NUM_5,   // COL4 = file e
    GPIO_NUM_18,  // COL5 = file f
    GPIO_NUM_19,  // COL6 = file g
    GPIO_NUM_21,  // COL7 = file h
};

// ---------------------------------------------------------------------------
//  Confirmation buttons (F-03 / FR-FW-007) - ADDITIVE, separate from the matrix.
//  Source = the original DGT chess-clock buttons, brought out via diode isolation
//  (terminal C: idle ~0V, ~1.5V when pressed; clock battery-minus = common ground).
//  That ~1.5V is BELOW the ESP32 digital-HIGH threshold, so the buttons are read on
//  ADC1 and thresholded in firmware (see main.cpp), NOT as plain digital inputs.
//  GPIO34/35 are input-only ADC1 pins (CH6/CH7), unused by the matrix; they have NO
//  internal pull, so each needs an EXTERNAL ~100k pull-down to GND to define idle 0V
//  (the isolation diode blocks at idle, so the node would otherwise float).
//  White confirms a white move/turn, black the black side - bare events only,
//  the firmware does no turn validation (the mobile re-derives whose turn it is).
// ---------------------------------------------------------------------------
static constexpr gpio_num_t kButtonWhitePin = GPIO_NUM_34;  // ADC1_CH6, white -> BUTTON_EVENT 0x00
static constexpr gpio_num_t kButtonBlackPin = GPIO_NUM_35;  // ADC1_CH7, black -> BUTTON_EVENT 0x01
