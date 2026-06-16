package org.rurbaniak.smartchessboard.domain.board

// The three mobile → board commands of the BLE contract (docs/reference/contract-surfaces.md §1.4).
// These are everything the host may write to the board; the reserved 0x84–0x9F command space stays
// unmodelled until a contract revision claims it.

/** A single mobile → board command (contract §1.4). */
sealed interface BoardCommand {
    /** SET_MODE (§1.4, tag 0x81): switch the board between normal game scanning and diagnostic mode (§1.6). */
    data class SetMode(
        val mode: BoardMode,
    ) : BoardCommand

    /** REQUEST_SNAPSHOT (§1.4, tag 0x82): ask the board to emit a BOARD_SNAPSHOT now. */
    data object RequestSnapshot : BoardCommand

    /** REQUEST_STATUS (§1.4, tag 0x83): ask the board to emit a DEVICE_STATUS now. */
    data object RequestStatus : BoardCommand
}

/** Board scanning mode set by SET_MODE: normal play, or diagnostic with ~10 Hz snapshots (§1.6). */
enum class BoardMode {
    GAME,
    DIAGNOSTIC,
}
