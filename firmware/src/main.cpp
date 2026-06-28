// Smart Chessboard - reed-switch 8x8 matrix firmware (F-03 game firmware).
//
// Scans an 8x8 reed-switch matrix and debounces it (via lib/debounce) into a
// 64-bit `stable` occupancy bitmap. This loop is the BLE PRODUCER: it owns
// `stable` and is the only context that reads it, so frames derived from it
// (BOARD_SNAPSHOT, the per-transition SQUARE_EVENT diff) are built here - a
// cross-task 64-bit read can tear on the dual-core ESP32 (plan Critical Details).
// The radio itself lives in ble_service.cpp (NimBLE peripheral, GATT, lifecycle,
// the mobile_command write handler, and the status/diagnostic timers).
//
// Phase 3 (this file's share): switch the matrix debounce to lib/debounce, stream
// one SQUARE_EVENT per debounced transition (never coalesced), read the two
// confirmation buttons into BUTTON_EVENTs, and service the BLE layer's
// Snapshot/Status/Burst requests on the task that owns `stable`. Game-mode events
// are enqueued only while a central is subscribed ("dead link delivers nothing").
//
// The serial console (UART0 @ 115200) keeps the in-place diagnostic render for
// local debugging - it is NOT on the BLE contract path. This build also renders
// each confirmation button's live ADC reading + debounced press count, so the
// DGT-clock buttons (~1.5V via diode isolation, read through ADC1 because that
// level is below the digital-HIGH threshold) can be verified by flashing and
// watching the terminal.
//
// NOT production: no anti-ghosting diodes assumed (safe with <=2 magnets, see
// README).

#include <cstddef>
#include <cstdint>
#include <cstdio>

#include "driver/gpio.h"
#include "esp_adc/adc_oneshot.h"  // confirmation buttons read via ADC1 (DGT ~1.5V)
#include "esp_rom_sys.h"     // esp_rom_delay_us
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "pins.h"
#include "board_protocol.h"
#include "debounce.h"
#include "ble_service.h"

static const char* TAG = "matrix";

static constexpr int     kSettleUs       = 50;   // column settle after driving a row LOW
static constexpr int     kScanIntervalMs = 20;   // scan cadence (~50 Hz, responsive)
static constexpr uint8_t kStableScans    = 4;    // debounce: N agreeing scans (~80 ms)

// Confirmation buttons read on ADC1 (DGT ~1.5V). Schmitt-trigger hysteresis: a press
// must exceed kButtonAdcHigh; release needs dropping below kButtonAdcLow. The dead band
// between them rejects the noisy mid-range, so we neither miscount nor flicker. With a
// proper external pull-down idle sits near 0; a real press reads ~1800-2000 raw. Tune
// these two once you can read a clean idle and a clean press in the serial line.
static constexpr int kButtonAdcHigh = 1500;   // raw > this => pressed
static constexpr int kButtonAdcLow  = 1000;   // raw < this => released
static adc_oneshot_unit_handle_t g_adc1 = nullptr;

// (row,col) -> square index. ROW=rank, COL=file. Remap here if the board is
// rotated/mirrored (e.g. file = 7 - col, rank = 7 - row).
static inline int square_index(int row, int col) {
    return col + 8 * row;   // = file + 8*rank => a1=0 ... h8=63
}

static void square_name(int idx, char* buf /*3 bytes*/) {
    buf[0] = static_cast<char>('a' + (idx & 7));
    buf[1] = static_cast<char>('1' + (idx >> 3));
    buf[2] = '\0';
}

static void configure_gpio() {
    // Diodes are installed cathode-toward-column, so we drive COL LOW and read ROW.
    uint64_t col_mask = 0;
    for (int c = 0; c < kNumCols; ++c) col_mask |= (1ULL << kColPins[c]);
    gpio_config_t col_cfg = {};
    col_cfg.pin_bit_mask = col_mask;
    col_cfg.mode         = GPIO_MODE_OUTPUT;
    col_cfg.pull_up_en   = GPIO_PULLUP_DISABLE;
    col_cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    col_cfg.intr_type    = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&col_cfg));
    for (int c = 0; c < kNumCols; ++c) gpio_set_level(kColPins[c], 1);  // idle HIGH

    uint64_t row_mask = 0;
    for (int r = 0; r < kNumRows; ++r) row_mask |= (1ULL << kRowPins[r]);
    gpio_config_t row_cfg = {};
    row_cfg.pin_bit_mask = row_mask;
    row_cfg.mode         = GPIO_MODE_INPUT;
    row_cfg.pull_up_en   = GPIO_PULLUP_ENABLE;
    row_cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    row_cfg.intr_type    = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&row_cfg));
}

// ---- Confirmation buttons (FR-FW-007) -------------------------------------
// DGT-clock buttons via diode isolation: idle ~0V, pressed ~1.5V. That 1.5V is
// BELOW the ESP32 digital-HIGH threshold (~2.475V @3V3), so a plain gpio_get_level
// reads it as 0. Instead we read the analog level on ADC1 (GPIO34=CH6 white,
// GPIO35=CH7 black - input-only ADC pins) and threshold it in software.
// An external pull-down (~100k to GND) on each pin defines the idle 0V - these pins
// have no internal pull, AND the isolation diode blocks at idle (so the node would
// otherwise float). Debounced like the matrix: a release->press edge surviving
// kStableScans agreeing reads counts ONE press (and emits ONE BUTTON_EVENT when a
// central is subscribed). No turn validation - the mobile re-derives whose turn it is.

struct Button {
    gpio_num_t             pin;      // wired GPIO (from pins.h) - for the serial label
    adc_channel_t          chan;     // matching ADC1 channel (GPIO34->CH6, GPIO35->CH7)
    board_protocol::Button id;
    uint8_t                agree;    // consecutive agreeing reads
    bool                   rawPrev;  // previous raw "pressed" reading
    bool                   pressed;  // committed (debounced) state
    bool                   hyst;     // Schmitt-trigger state from the ADC hysteresis
    uint32_t               presses;  // debounced press count
    int                    lastRaw;  // most recent ADC reading (for the serial line)
};

static Button g_buttons[2] = {
    {kButtonWhitePin, ADC_CHANNEL_6, board_protocol::Button::White, 0, false, false, false, 0, 0},
    {kButtonBlackPin, ADC_CHANNEL_7, board_protocol::Button::Black, 0, false, false, false, 0, 0},
};

static void configure_buttons() {
    // GPIO34/35 are input-only ADC1 pins; no GPIO direction/pull config is possible
    // (idle 0V comes from the external pull-down). Set up ADC1 one-shot on both.
    adc_oneshot_unit_init_cfg_t init = {};
    init.unit_id = ADC_UNIT_1;
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init, &g_adc1));

    adc_oneshot_chan_cfg_t ch = {};
    ch.atten    = ADC_ATTEN_DB_12;       // full-scale ~3.1V, covers the ~1.5V press level
    ch.bitwidth = ADC_BITWIDTH_DEFAULT;  // 12-bit (0..4095)
    for (const Button& b : g_buttons) {
        ESP_ERROR_CHECK(adc_oneshot_config_channel(g_adc1, b.chan, &ch));
    }
}

// Feed one raw read; return true exactly once on a confirmed release->press edge.
static bool button_step(Button& b, bool rawPressed) {
    bool edge = false;
    if (rawPressed == b.rawPrev) {
        if (b.agree < kStableScans) ++b.agree;
        if (b.agree == kStableScans && b.pressed != rawPressed) {
            b.pressed = rawPressed;
            edge = rawPressed;             // emit on the press, not the release
        }
    } else {
        b.agree = 0;                       // bounce/noise -> restart the window
    }
    b.rawPrev = rawPressed;
    return edge;
}

// Producer-owned scratch for the per-scan SQUARE_EVENT diff. File-scope (not on
// the app_main stack) and touched only by the single producer task. Capacity 64
// == one per square, so deriveSquareEvents can never truncate (§1.3: never drop).
static board_protocol::Frame g_square_events[64];

// One full scan -> 64-bit occupancy bitmap (bit = square index).
// Drives COL LOW (cathode side of reversed diodes) and reads ROW (anode side).
static uint64_t scan_matrix() {
    uint64_t occ = 0;
    for (int c = 0; c < kNumCols; ++c) {
        gpio_set_level(kColPins[c], 0);
        esp_rom_delay_us(kSettleUs);
        for (int r = 0; r < kNumRows; ++r)
            if (gpio_get_level(kRowPins[r]) == 0)
                occ |= (1ULL << square_index(r, c));
        gpio_set_level(kColPins[c], 1);
    }
    return occ;
}

// Redraw the whole view in place (ANSI clear screen + scrollback + home cursor).
static void render(uint64_t occ, uint64_t prev) {
    printf("\033[2J\033[3J\033[H");   // clear + home -> no scrolling
    printf("Smart Chessboard - reed matrix  (in-place, redraws on change)\n\n");

    for (int rank = 7; rank >= 0; --rank) {
        printf("%d ", rank + 1);
        for (int file = 0; file < 8; ++file)
            printf("%c ", (occ >> (file + 8 * rank)) & 1ULL ? 'X' : '.');
        putchar('\n');
    }
    printf("  a b c d e f g h\n\n");

    char line[300];
    int n = snprintf(line, sizeof(line), "Closed:");
    if (!occ) {
        snprintf(line + n, sizeof(line) - n, " (none)");
    } else {
        for (int i = 0; i < 64 && n < (int)sizeof(line) - 4; ++i)
            if ((occ >> i) & 1ULL) { char nm[3]; square_name(i, nm);
                n += snprintf(line + n, sizeof(line) - n, " %s", nm); }
    }
    printf("%s\n", line);

    printf("Buttons (ADC1):  White GPIO%d raw=%4d ~%4dmV %s presses=%lu    Black GPIO%d raw=%4d ~%4dmV %s presses=%lu\n",
           static_cast<int>(g_buttons[0].pin), g_buttons[0].lastRaw,
           g_buttons[0].lastRaw * 3100 / 4095, g_buttons[0].pressed ? "DOWN" : "up  ",
           static_cast<unsigned long>(g_buttons[0].presses),
           static_cast<int>(g_buttons[1].pin), g_buttons[1].lastRaw,
           g_buttons[1].lastRaw * 3100 / 4095, g_buttons[1].pressed ? "DOWN" : "up  ",
           static_cast<unsigned long>(g_buttons[1].presses));

    const uint64_t added = occ & ~prev, removed = prev & ~occ;
    if (added || removed) {
        char d[300]; int m = snprintf(d, sizeof(d), "Change:");
        for (int i = 0; i < 64; ++i) if ((added   >> i) & 1ULL) { char nm[3]; square_name(i, nm);
            m += snprintf(d + m, sizeof(d) - m, " +%s", nm); }
        for (int i = 0; i < 64; ++i) if ((removed >> i) & 1ULL) { char nm[3]; square_name(i, nm);
            m += snprintf(d + m, sizeof(d) - m, " -%s", nm); }
        printf("%s\n", d);
    }
    fflush(stdout);
}

extern "C" void app_main(void) {
    ESP_LOGI(TAG, "Reed matrix + BLE game firmware - UART0 @ 115200, debounced");
    configure_gpio();
    configure_buttons();
    ble_service::init();           // NVS + NimBLE peripheral + notify consumer

    debounce::Debouncer deb;
    debounce::init(deb, scan_matrix());   // seed rawPrev with the first scan

    uint64_t prevStable = 0;       // last state diffed into SQUARE_EVENTs
    uint64_t shown      = ~0ULL;    // last rendered (sentinel forces first draw)

    while (true) {
        const uint64_t raw    = scan_matrix();
        const uint64_t stable = debounce::step(deb, raw);

        // Per-transition SQUARE_EVENTs (mandatory, never coalesced — the lift on a
        // capture destination is the discriminator SequenceInterpreter needs).
        // prevStable always tracks `stable`, so a reconnect's fresh snapshot
        // starts a clean diff; events are enqueued only while subscribed (§1.7).
        if (stable != prevStable) {
            if (ble_service::is_subscribed()) {
                const size_t n = board_protocol::deriveSquareEvents(
                    prevStable, stable, g_square_events, 64);
                for (size_t i = 0; i < n; ++i) {
                    ble_service::enqueue_frame(g_square_events[i]);
                }
            }
            prevStable = stable;
        }

        // Confirmation buttons via ADC1, with Schmitt-trigger hysteresis so the noisy
        // mid-range neither miscounts nor flickers the screen. Redraw ONLY when a button
        // changes committed state (up<->down) or a press is counted - never on raw wobble.
        bool buttonsChanged = false;
        for (Button& b : g_buttons) {
            int raw = 0;
            adc_oneshot_read(g_adc1, b.chan, &raw);
            b.lastRaw = raw;
            if      (raw > kButtonAdcHigh) b.hyst = true;
            else if (raw < kButtonAdcLow)  b.hyst = false;   // else: hold (dead band)
            const bool wasPressed = b.pressed;
            if (button_step(b, b.hyst)) {
                ++b.presses;
                if (ble_service::is_subscribed()) {
                    ble_service::enqueue_frame(board_protocol::encodeButtonEvent(b.id));
                }
            }
            if (b.pressed != wasPressed) buttonsChanged = true;
        }

        // Service BLE-layer requests on the task that owns `stable` — no
        // cross-task 64-bit read (plan Critical Details). Burst on CCCD subscribe;
        // Snapshot on REQUEST_SNAPSHOT / each diagnostic tick; Status on
        // REQUEST_STATUS / the ~30 s periodic timer.
        ble_service::Request req;
        while (ble_service::poll_request(req)) {
            switch (req) {
            case ble_service::Request::Snapshot:
                ble_service::enqueue_frame(board_protocol::encodeSnapshot(stable));
                break;
            case ble_service::Request::Status:
                ble_service::enqueue_frame(ble_service::build_status_frame());
                break;
            case ble_service::Request::Burst:
                ble_service::enqueue_frame(board_protocol::encodeSnapshot(stable));
                ble_service::enqueue_frame(ble_service::build_status_frame());
                break;
            }
        }

        if (stable != shown || buttonsChanged) {
            render(stable, (shown == ~0ULL) ? stable : shown);  // no diff on first draw
            shown = stable;
        }
        vTaskDelay(pdMS_TO_TICKS(kScanIntervalMs));
    }
}
