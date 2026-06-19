// Smart Chessboard — NimBLE peripheral for the §1 BLE board contract.
//
// This unit owns the radio: it brings up NVS + the NimBLE host, advertises as
// `SmartChessboard-XXXX`, accepts a single Just-Works-bonded central, exposes
// the one GATT service (`board_event` notify + `mobile_command` write), and
// runs the connection lifecycle (on-subscribe BOARD_SNAPSHOT→DEVICE_STATUS
// burst, re-advertise on disconnect). The three GATT UUIDs are recorded in
// docs/reference/contract-surfaces.md §1.2.
//
// Concurrency (research D7 / plan Critical Details): the scan/debounce loop in
// app_main is the PRODUCER — it owns the 64-bit `stable` occupancy and is the
// ONLY context that may read it (a cross-task 64-bit read can tear on the
// dual-core ESP32). The BLE layer therefore never reads `stable`; when it needs
// a frame derived from it, it posts a Request that the producer services by
// building the frame on its own task and handing it back via enqueue_frame().
// A dedicated consumer task inside this unit drains those frames and notifies
// the subscribed central.
//
// Phase 2 scope: advertising, bonding, lifecycle, and the on-subscribe burst.
// Phase 3 layers per-transition SQUARE_EVENTs, buttons, mobile_command dispatch,
// and the diagnostic/periodic-status timers onto this same producer/consumer
// spine.

#pragma once

#include <cstdint>

#include "board_protocol.h"

namespace ble_service {

// A piece of work the BLE layer asks the scan/producer task (app_main) to
// perform, because frames derived from `stable` MUST be built on the task that
// owns it. Phase 2 only ever posts Burst (on CCCD subscribe); Snapshot/Status
// are wired to mobile_command writes and timers in Phase 3.
enum class Request : uint8_t {
    Snapshot,  // enqueue one BOARD_SNAPSHOT built from `stable`
    Status,    // enqueue one DEVICE_STATUS
    Burst,     // enqueue BOARD_SNAPSHOT then DEVICE_STATUS (connect burst)
};

// Bring up NVS + the NimBLE peripheral and spawn the notify-consumer task.
// Call once from app_main before entering the scan loop.
void init();

// Non-blocking: pop the next pending Request the BLE layer queued. Returns true
// and sets `out` if one was pending, false otherwise. Called by the producer
// (scan loop) each iteration so it can build `stable`-derived frames itself.
bool poll_request(Request& out);

// Producer entry point: hand an already-encoded board_event frame to the
// consumer task, which notifies it iff a central is connected and subscribed.
// Safe to call from the scan task. Drops silently if the outbound queue is full.
void enqueue_frame(const board_protocol::Frame& frame);

// Build a DEVICE_STATUS frame: battery 100 (USB constant), firmware 1.0.0,
// uptime from esp_timer (unsigned seconds). Reads only constants + uptime —
// never `stable` — so any context may call it.
board_protocol::Frame build_status_frame();

}  // namespace ble_service
