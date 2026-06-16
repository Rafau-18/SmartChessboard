package org.rurbaniak.smartchessboard.data.board.protocol

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.FirmwareVersion
import org.rurbaniak.smartchessboard.domain.board.SquareEventType

// Bidirectional translation between the typed board vocabulary (domain/board) and the BLE wire
// frames of the contract (docs/reference/contract-surfaces.md §1.3 board → mobile, §1.4 mobile →
// board). The encoder is the board side (used by the F-02 emulator, later a reference for the
// firmware); the decoder is the mobile side (reused verbatim by the S-09 BLE adapter).
//
// Decoding is total: every well-formed frame yields a Decoded result, every malformed frame yields
// a Malformed result naming the reason — it never throws on hostile input. This keeps the S-09 BLE
// adapter robust against truncated notifications and future firmware that uses the reserved tag
// space (§1.4 reserves 0x84–0x9F).

/** Result of decoding a board → mobile frame (§1.3): a typed [BoardEvent] or a described failure. */
sealed interface EventDecodeResult {
    data class Decoded(
        val event: BoardEvent,
    ) : EventDecodeResult

    /** The raw [bytes] could not be decoded; [reason] names why (unknown tag, bad length, out-of-range field). */
    class Malformed(
        val bytes: ByteArray,
        val reason: String,
    ) : EventDecodeResult
}

/** Result of decoding a mobile → board frame (§1.4): a typed [BoardCommand] or a described failure. */
sealed interface CommandDecodeResult {
    data class Decoded(
        val command: BoardCommand,
    ) : CommandDecodeResult

    /** The raw [bytes] could not be decoded; [reason] names why (unknown/reserved tag, bad length, out-of-range field). */
    class Malformed(
        val bytes: ByteArray,
        val reason: String,
    ) : CommandDecodeResult
}

/**
 * Pure, stateless §1.3/§1.4 wire codec. Snapshot bit packing is locked as: byte `i` bit `j`
 * (LSB-first) = square `i*8 + j`, so byte 0 holds squares 0–7 (rank 1, a1 = byte 0 bit 0) and byte 7
 * holds squares 56–63 (h8 = byte 7 bit 7) — the natural little-endian-by-square extension of §1.3's
 * "bit N = square N". `DEVICE_STATUS` uptime is an unsigned 32-bit little-endian field held in a Long.
 */
object BoardWireCodec {
    // board → mobile tags (§1.3)
    private const val TAG_BOARD_SNAPSHOT = 0x01
    private const val TAG_SQUARE_EVENT = 0x02
    private const val TAG_BUTTON_EVENT = 0x03
    private const val TAG_DEVICE_STATUS = 0x04

    // mobile → board tags (§1.4)
    private const val TAG_SET_MODE = 0x81
    private const val TAG_REQUEST_SNAPSHOT = 0x82
    private const val TAG_REQUEST_STATUS = 0x83

    // SQUARE_EVENT high-2-bit event codes (§1.3): 00 = lift, 01 = place; 10/11 reserved.
    private const val EVENT_LIFT = 0b00
    private const val EVENT_PLACE = 0b01
    private const val SQUARE_MASK = 0x3F
    private const val EVENT_SHIFT = 6

    // payload byte values (§1.3/§1.4)
    private const val BUTTON_WHITE = 0x00
    private const val BUTTON_BLACK = 0x01
    private const val MODE_GAME = 0x00
    private const val MODE_DIAGNOSTIC = 0x01

    private const val SNAPSHOT_FRAME_SIZE = 9 // 1 tag + 8 occupancy bytes
    private const val DEVICE_STATUS_FRAME_SIZE = 9 // 1 tag + battery + 3 version + 4 uptime
    private const val SINGLE_PAYLOAD_FRAME_SIZE = 2 // 1 tag + 1 payload byte
    private const val TAG_ONLY_FRAME_SIZE = 1
    private const val BYTE_MASK = 0xFF

    fun encodeEvent(event: BoardEvent): ByteArray =
        when (event) {
            is BoardEvent.BoardSnapshot -> {
                val frame = ByteArray(SNAPSHOT_FRAME_SIZE)
                frame[0] = TAG_BOARD_SNAPSHOT.toByte()
                for (i in 0 until 8) {
                    frame[1 + i] = ((event.occupancy ushr (i * 8)) and BYTE_MASK.toLong()).toByte()
                }
                frame
            }

            is BoardEvent.SquareEvent -> {
                val eventBits =
                    when (event.type) {
                        SquareEventType.LIFT -> EVENT_LIFT
                        SquareEventType.PLACE -> EVENT_PLACE
                    }
                byteArrayOf(TAG_SQUARE_EVENT.toByte(), ((eventBits shl EVENT_SHIFT) or event.square).toByte())
            }

            is BoardEvent.ButtonEvent -> {
                val payload =
                    when (event.button) {
                        BoardButton.WHITE -> BUTTON_WHITE
                        BoardButton.BLACK -> BUTTON_BLACK
                    }
                byteArrayOf(TAG_BUTTON_EVENT.toByte(), payload.toByte())
            }

            is BoardEvent.DeviceStatus -> {
                byteArrayOf(
                    TAG_DEVICE_STATUS.toByte(),
                    event.batteryPct.toByte(),
                    event.firmwareVersion.major.toByte(),
                    event.firmwareVersion.minor.toByte(),
                    event.firmwareVersion.patch.toByte(),
                    (event.uptimeSeconds and BYTE_MASK.toLong()).toByte(),
                    ((event.uptimeSeconds ushr 8) and BYTE_MASK.toLong()).toByte(),
                    ((event.uptimeSeconds ushr 16) and BYTE_MASK.toLong()).toByte(),
                    ((event.uptimeSeconds ushr 24) and BYTE_MASK.toLong()).toByte(),
                )
            }
        }

    fun decodeEvent(bytes: ByteArray): EventDecodeResult {
        if (bytes.isEmpty()) return EventDecodeResult.Malformed(bytes, "empty frame")
        return when (val tag = bytes[0].toInt() and BYTE_MASK) {
            TAG_BOARD_SNAPSHOT -> decodeSnapshot(bytes)
            TAG_SQUARE_EVENT -> decodeSquareEvent(bytes)
            TAG_BUTTON_EVENT -> decodeButtonEvent(bytes)
            TAG_DEVICE_STATUS -> decodeDeviceStatus(bytes)
            else -> EventDecodeResult.Malformed(bytes, "unknown event tag 0x${tag.toHex()}")
        }
    }

    private fun decodeSnapshot(bytes: ByteArray): EventDecodeResult {
        if (bytes.size != SNAPSHOT_FRAME_SIZE) {
            return EventDecodeResult.Malformed(
                bytes,
                "BOARD_SNAPSHOT expects $SNAPSHOT_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        var occupancy = 0L
        for (i in 0 until 8) {
            occupancy = occupancy or ((bytes[1 + i].toLong() and BYTE_MASK.toLong()) shl (i * 8))
        }
        return EventDecodeResult.Decoded(BoardEvent.BoardSnapshot(occupancy))
    }

    private fun decodeSquareEvent(bytes: ByteArray): EventDecodeResult {
        if (bytes.size != SINGLE_PAYLOAD_FRAME_SIZE) {
            return EventDecodeResult.Malformed(
                bytes,
                "SQUARE_EVENT expects $SINGLE_PAYLOAD_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        val payload = bytes[1].toInt() and BYTE_MASK
        val square = payload and SQUARE_MASK
        val type =
            when (val eventBits = payload ushr EVENT_SHIFT) {
                EVENT_LIFT -> SquareEventType.LIFT

                EVENT_PLACE -> SquareEventType.PLACE

                else -> return EventDecodeResult.Malformed(
                    bytes,
                    "SQUARE_EVENT reserved event bits 0b${eventBits.toString(2)}",
                )
            }
        return EventDecodeResult.Decoded(BoardEvent.SquareEvent(square, type))
    }

    private fun decodeButtonEvent(bytes: ByteArray): EventDecodeResult {
        if (bytes.size != SINGLE_PAYLOAD_FRAME_SIZE) {
            return EventDecodeResult.Malformed(
                bytes,
                "BUTTON_EVENT expects $SINGLE_PAYLOAD_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        val button =
            when (val payload = bytes[1].toInt() and BYTE_MASK) {
                BUTTON_WHITE -> BoardButton.WHITE
                BUTTON_BLACK -> BoardButton.BLACK
                else -> return EventDecodeResult.Malformed(bytes, "BUTTON_EVENT unknown button 0x${payload.toHex()}")
            }
        return EventDecodeResult.Decoded(BoardEvent.ButtonEvent(button))
    }

    private fun decodeDeviceStatus(bytes: ByteArray): EventDecodeResult {
        if (bytes.size != DEVICE_STATUS_FRAME_SIZE) {
            return EventDecodeResult.Malformed(
                bytes,
                "DEVICE_STATUS expects $DEVICE_STATUS_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        val battery = bytes[1].toInt() and BYTE_MASK
        val version =
            FirmwareVersion(
                major = bytes[2].toInt() and BYTE_MASK,
                minor = bytes[3].toInt() and BYTE_MASK,
                patch = bytes[4].toInt() and BYTE_MASK,
            )
        var uptime = 0L
        for (i in 0 until 4) {
            uptime = uptime or ((bytes[5 + i].toLong() and BYTE_MASK.toLong()) shl (i * 8))
        }
        return EventDecodeResult.Decoded(BoardEvent.DeviceStatus(battery, version, uptime))
    }

    fun encodeCommand(command: BoardCommand): ByteArray =
        when (command) {
            is BoardCommand.SetMode -> {
                val mode =
                    when (command.mode) {
                        BoardMode.GAME -> MODE_GAME
                        BoardMode.DIAGNOSTIC -> MODE_DIAGNOSTIC
                    }
                byteArrayOf(TAG_SET_MODE.toByte(), mode.toByte())
            }

            BoardCommand.RequestSnapshot -> {
                byteArrayOf(TAG_REQUEST_SNAPSHOT.toByte())
            }

            BoardCommand.RequestStatus -> {
                byteArrayOf(TAG_REQUEST_STATUS.toByte())
            }
        }

    fun decodeCommand(bytes: ByteArray): CommandDecodeResult {
        if (bytes.isEmpty()) return CommandDecodeResult.Malformed(bytes, "empty frame")
        return when (val tag = bytes[0].toInt() and BYTE_MASK) {
            TAG_SET_MODE -> {
                decodeSetMode(bytes)
            }

            TAG_REQUEST_SNAPSHOT -> {
                decodeTagOnlyCommand(bytes, BoardCommand.RequestSnapshot, "REQUEST_SNAPSHOT")
            }

            TAG_REQUEST_STATUS -> {
                decodeTagOnlyCommand(bytes, BoardCommand.RequestStatus, "REQUEST_STATUS")
            }

            else -> {
                CommandDecodeResult.Malformed(
                    bytes,
                    "unknown command tag 0x${tag.toHex()} (reserved space 0x84–0x9F)",
                )
            }
        }
    }

    private fun decodeSetMode(bytes: ByteArray): CommandDecodeResult {
        if (bytes.size != SINGLE_PAYLOAD_FRAME_SIZE) {
            return CommandDecodeResult.Malformed(
                bytes,
                "SET_MODE expects $SINGLE_PAYLOAD_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        val mode =
            when (val payload = bytes[1].toInt() and BYTE_MASK) {
                MODE_GAME -> BoardMode.GAME
                MODE_DIAGNOSTIC -> BoardMode.DIAGNOSTIC
                else -> return CommandDecodeResult.Malformed(bytes, "SET_MODE unknown mode 0x${payload.toHex()}")
            }
        return CommandDecodeResult.Decoded(BoardCommand.SetMode(mode))
    }

    private fun decodeTagOnlyCommand(
        bytes: ByteArray,
        command: BoardCommand,
        name: String,
    ): CommandDecodeResult {
        if (bytes.size != TAG_ONLY_FRAME_SIZE) {
            return CommandDecodeResult.Malformed(bytes, "$name expects $TAG_ONLY_FRAME_SIZE byte, got ${bytes.size}")
        }
        return CommandDecodeResult.Decoded(command)
    }

    private fun Int.toHex(): String = toString(16)
}
