package org.rurbaniak.smartchessboard.domain.board

import org.rurbaniak.smartchessboard.domain.chess.isValidSquare

// The four board → mobile messages of the BLE contract (docs/reference/contract-surfaces.md §1.3),
// modelled in the board's own terms: occupancy and squares, never chess pieces or moves. A real
// reed-switch board has no chess knowledge — it only senses which squares are physically occupied.
// Square indices follow the single convention in domain/chess/Square.kt (a1 = 0, h8 = 63).

/** A single board → mobile message (contract §1.3). */
sealed interface BoardEvent {
    /**
     * SQUARE_EVENT (§1.3, tag 0x02): a piece was lifted from or placed onto [square]. The board
     * reports raw sensor transitions; interpreting a lift/place sequence as a move is the host's job.
     */
    data class SquareEvent(
        val square: Int,
        val type: SquareEventType,
    ) : BoardEvent

    /**
     * BUTTON_EVENT (§1.3, tag 0x03): a player pressed their confirm button. Which physical button
     * maps to which side is fixed by the contract, not by game state.
     */
    data class ButtonEvent(
        val button: BoardButton,
    ) : BoardEvent

    /**
     * BOARD_SNAPSHOT (§1.3, tag 0x01): the full 64-square occupancy as a bitmap. Bit N set means
     * square N is occupied (a1 = bit 0, h8 = bit 63). Emitted on every (re)connect and on request,
     * and at ~10 Hz in diagnostic mode (§1.6).
     */
    data class BoardSnapshot(
        val occupancy: Long,
    ) : BoardEvent {
        /** Whether [square] (0..63) is occupied in this snapshot. */
        fun isOccupied(square: Int): Boolean {
            require(isValidSquare(square)) { "square must be in 0..63, was $square" }
            return (occupancy ushr square) and 1L == 1L
        }
    }

    /**
     * DEVICE_STATUS (§1.3, tag 0x04): battery level, firmware version, and uptime. Emitted on
     * connect and periodically thereafter.
     */
    data class DeviceStatus(
        val batteryPct: Int,
        val firmwareVersion: FirmwareVersion,
        val uptimeSeconds: Long,
    ) : BoardEvent
}

/** Direction of a SQUARE_EVENT: a piece leaving (LIFT) or arriving (PLACE) on a square. */
enum class SquareEventType {
    LIFT,
    PLACE,
}

/** The two confirm buttons of a BUTTON_EVENT, fixed by §1.3 (0x00 white, 0x01 black). */
enum class BoardButton {
    WHITE,
    BLACK,
}

/** Firmware version carried by DEVICE_STATUS (§1.3: 3 bytes, major/minor/patch). */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
)
