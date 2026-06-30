package org.rurbaniak.smartchessboard.data.board.ble

// Single source of truth for the BLE GATT identifiers the S-09 board adapter scans, subscribes, and
// writes against. Byte-identical to docs/reference/contract-surfaces.md §1.2 and the firmware
// (firmware/src/ble_service.cpp). One custom 128-bit base `787e000X-15a4-4fc9-a469-05096dbad1a1`,
// where X = 1 service / 2 board_event / 3 mobile_command.
//
// String form, in commonMain, so both the androidMain and iosMain Kable adapters share these exact
// bytes. The values are frozen by the contract — do not edit without updating §1.2 and the firmware
// in lockstep.
object BleUuids {
    /** Primary chess-board GATT service. */
    const val SERVICE = "787e0001-15a4-4fc9-a469-05096dbad1a1"

    /** `board_event` — board → mobile, notify. The adapter subscribes here for the event stream. */
    const val BOARD_EVENT = "787e0002-15a4-4fc9-a469-05096dbad1a1"

    /** `mobile_command` — mobile → board, write. The adapter writes encoded commands here. */
    const val MOBILE_COMMAND = "787e0003-15a4-4fc9-a469-05096dbad1a1"

    /** Standard Client Characteristic Configuration Descriptor on `board_event`; written to subscribe. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}
