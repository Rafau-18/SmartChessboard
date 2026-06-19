// Smart Chessboard - reed-switch 8x8 matrix firmware (F-03 game firmware).
//
// Scans an 8x8 reed-switch matrix and debounces it into a 64-bit `stable`
// occupancy bitmap. This loop is also the BLE PRODUCER: it owns `stable` and is
// the only context that reads it, so frames derived from it (BOARD_SNAPSHOT) are
// built here on a request from the BLE layer (a cross-task 64-bit read can tear
// on the dual-core ESP32 - see plan Critical Details). The radio itself lives in
// ble_service.cpp (NimBLE peripheral, one GATT service, lifecycle).
//
// Phase 2: advertising / bonding / on-subscribe BOARD_SNAPSHOT->DEVICE_STATUS
// burst. Per-transition SQUARE_EVENTs, buttons, commands, and timers are Phase 3.
//
// The serial console (UART0 @ 115200) keeps the in-place diagnostic render for
// local debugging - it is NOT on the BLE contract path.
//
// NOT production: no anti-ghosting diodes assumed (safe with <=2 magnets, see
// README).

#include <cstdint>
#include <cstdio>

#include "driver/gpio.h"
#include "esp_rom_sys.h"     // esp_rom_delay_us
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "pins.h"
#include "board_protocol.h"
#include "ble_service.h"

static const char* TAG = "matrix";

static constexpr int     kSettleUs       = 50;   // column settle after driving a row LOW
static constexpr int     kScanIntervalMs = 20;   // scan cadence (~50 Hz, responsive)
static constexpr uint8_t kStableScans    = 4;    // debounce: N agreeing scans (~80 ms)

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
    uint64_t row_mask = 0;
    for (int r = 0; r < kNumRows; ++r) row_mask |= (1ULL << kRowPins[r]);
    gpio_config_t row_cfg = {};
    row_cfg.pin_bit_mask = row_mask;
    row_cfg.mode         = GPIO_MODE_OUTPUT;
    row_cfg.pull_up_en   = GPIO_PULLUP_DISABLE;
    row_cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    row_cfg.intr_type    = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&row_cfg));
    for (int r = 0; r < kNumRows; ++r) gpio_set_level(kRowPins[r], 1);  // idle HIGH

    uint64_t col_mask = 0;
    for (int c = 0; c < kNumCols; ++c) col_mask |= (1ULL << kColPins[c]);
    gpio_config_t col_cfg = {};
    col_cfg.pin_bit_mask = col_mask;
    col_cfg.mode         = GPIO_MODE_INPUT;
    col_cfg.pull_up_en   = GPIO_PULLUP_ENABLE;
    col_cfg.pull_down_en = GPIO_PULLDOWN_DISABLE;
    col_cfg.intr_type    = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&col_cfg));
}

// One full scan -> 64-bit occupancy bitmap (bit = square index).
static uint64_t scan_matrix() {
    uint64_t occ = 0;
    for (int r = 0; r < kNumRows; ++r) {
        gpio_set_level(kRowPins[r], 0);
        esp_rom_delay_us(kSettleUs);
        for (int c = 0; c < kNumCols; ++c)
            if (gpio_get_level(kColPins[c]) == 0)
                occ |= (1ULL << square_index(r, c));
        gpio_set_level(kRowPins[r], 1);
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
    ESP_LOGI(TAG, "Reed matrix monitor - UART0 @ 115200, change-driven + debounced");
    configure_gpio();
    ble_service::init();           // NVS + NimBLE peripheral + notify consumer

    uint64_t stable = 0;            // committed (debounced) board state
    uint64_t shown  = ~0ULL;        // last rendered (sentinel forces first draw)
    uint64_t rawPrev = scan_matrix();
    uint8_t  agree[64] = {0};       // per-square consecutive-agreement counter

    while (true) {
        const uint64_t raw = scan_matrix();
        for (int i = 0; i < 64; ++i) {
            const uint64_t bit = (raw >> i) & 1ULL, prevBit = (rawPrev >> i) & 1ULL;
            if (bit == prevBit) {
                if (agree[i] < kStableScans) ++agree[i];
                if (agree[i] == kStableScans) {            // commit debounced bit
                    if (bit) stable |=  (1ULL << i);
                    else     stable &= ~(1ULL << i);
                }
            } else {
                agree[i] = 0;                              // bounce/noise -> restart
            }
        }
        rawPrev = raw;

        // Service BLE-layer requests on the task that owns `stable` — no
        // cross-task 64-bit read (plan Critical Details). Phase 2 only ever
        // sees Burst (on CCCD subscribe); Snapshot/Status arrive in Phase 3.
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

        if (stable != shown) {
            render(stable, (shown == ~0ULL) ? stable : shown);  // no diff on first draw
            shown = stable;
        }
        vTaskDelay(pdMS_TO_TICKS(kScanIntervalMs));
    }
}
