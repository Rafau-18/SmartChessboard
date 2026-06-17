package org.rurbaniak.smartchessboard.data.board.protocol

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.FirmwareVersion
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Golden-frame vectors for the §1.3/§1.4 wire codec. Every literal frame here is hand-derived from
// docs/reference/contract-surfaces.md (the section is cited per group) and asserted against the
// codec in BOTH directions independently — encoder output must equal the literal, and the literal
// fed to the decoder must yield the typed value. This is the crypto test-vector discipline: a
// shared encode/decode bug cannot validate itself because the expected bytes never came from the
// codec under test. Round-trip property checks appear only as a supplement, never as a substitute.
//
// TODO(F-02, manual gate 2.3/2.4): these golden vectors are hand-derived from §1.3 and still need an
//  independent human cross-check — a green test with a wrong hand-derived vector proves nothing.
//  Deferred worksheet: context/changes/reed-board-emulator/manual-verification.md
class BoardWireCodecTest {
    // --- SQUARE_EVENT (§1.3, tag 0x02): square in low 6 bits, event in high 2 bits (00 lift, 01 place) ---

    @Test
    fun encodesSquareEventGoldenFrames() {
        // a1 = square 0; h8 = square 63 (corners). e2 = 12, e4 = 28 (file e=4: 4+8*rank).
        assertContentEquals(bytes(0x02, 0x00), BoardWireCodec.encodeEvent(square(0, SquareEventType.LIFT)))
        assertContentEquals(bytes(0x02, 0x40), BoardWireCodec.encodeEvent(square(0, SquareEventType.PLACE)))
        assertContentEquals(bytes(0x02, 0x3F), BoardWireCodec.encodeEvent(square(63, SquareEventType.LIFT)))
        assertContentEquals(bytes(0x02, 0x7F), BoardWireCodec.encodeEvent(square(63, SquareEventType.PLACE)))
        assertContentEquals(bytes(0x02, 0x0C), BoardWireCodec.encodeEvent(square(12, SquareEventType.LIFT)))
        assertContentEquals(bytes(0x02, 0x5C), BoardWireCodec.encodeEvent(square(28, SquareEventType.PLACE)))
    }

    @Test
    fun decodesSquareEventGoldenFrames() {
        assertEquals(square(0, SquareEventType.LIFT), decodedEvent(bytes(0x02, 0x00)))
        assertEquals(square(0, SquareEventType.PLACE), decodedEvent(bytes(0x02, 0x40)))
        assertEquals(square(63, SquareEventType.LIFT), decodedEvent(bytes(0x02, 0x3F)))
        assertEquals(square(63, SquareEventType.PLACE), decodedEvent(bytes(0x02, 0x7F)))
        assertEquals(square(12, SquareEventType.LIFT), decodedEvent(bytes(0x02, 0x0C)))
        assertEquals(square(28, SquareEventType.PLACE), decodedEvent(bytes(0x02, 0x5C)))
    }

    @Test
    fun squareEventRoundTripsEverySquareAndType() {
        for (sq in 0..63) {
            for (type in SquareEventType.entries) {
                val event = square(sq, type)
                assertEquals(event, decodedEvent(BoardWireCodec.encodeEvent(event)))
            }
        }
    }

    // --- BUTTON_EVENT (§1.3, tag 0x03): 0x00 white, 0x01 black ---

    @Test
    fun encodesButtonEventGoldenFrames() {
        assertContentEquals(bytes(0x03, 0x00), BoardWireCodec.encodeEvent(BoardEvent.ButtonEvent(BoardButton.WHITE)))
        assertContentEquals(bytes(0x03, 0x01), BoardWireCodec.encodeEvent(BoardEvent.ButtonEvent(BoardButton.BLACK)))
    }

    @Test
    fun decodesButtonEventGoldenFrames() {
        assertEquals(BoardEvent.ButtonEvent(BoardButton.WHITE), decodedEvent(bytes(0x03, 0x00)))
        assertEquals(BoardEvent.ButtonEvent(BoardButton.BLACK), decodedEvent(bytes(0x03, 0x01)))
    }

    // --- BOARD_SNAPSHOT (§1.3, tag 0x01): 8 occupancy bytes, byte i bit j (LSB-first) = square i*8+j ---

    @Test
    fun encodesSnapshotGoldenFrames() {
        assertContentEquals(
            bytes(0x01, 0, 0, 0, 0, 0, 0, 0, 0),
            BoardWireCodec.encodeEvent(BoardEvent.BoardSnapshot(0L)),
        )
        assertContentEquals(
            bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
            BoardWireCodec.encodeEvent(BoardEvent.BoardSnapshot(ALL_OCCUPIED)),
        )
        // Start position: ranks 1,2 (squares 0–15) and ranks 7,8 (squares 48–63) occupied.
        assertContentEquals(
            bytes(0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF),
            BoardWireCodec.encodeEvent(BoardEvent.BoardSnapshot(START_OCCUPANCY)),
        )
        // a2 = square 8 → byte 1, bit 0 (pins the byte index).
        assertContentEquals(
            bytes(0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0),
            BoardWireCodec.encodeEvent(
                BoardEvent.BoardSnapshot(1L shl 8),
            ),
        )
        // h1 = square 7 → byte 0, bit 7 (pins LSB-first bit ordering within a byte).
        assertContentEquals(
            bytes(0x01, 0x80, 0, 0, 0, 0, 0, 0, 0),
            BoardWireCodec.encodeEvent(
                BoardEvent.BoardSnapshot(1L shl 7),
            ),
        )
    }

    @Test
    fun decodesSnapshotGoldenFrames() {
        assertEquals(BoardEvent.BoardSnapshot(0L), decodedEvent(bytes(0x01, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(
            BoardEvent.BoardSnapshot(ALL_OCCUPIED),
            decodedEvent(bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)),
        )
        assertEquals(
            BoardEvent.BoardSnapshot(START_OCCUPANCY),
            decodedEvent(bytes(0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF)),
        )
        assertEquals(BoardEvent.BoardSnapshot(1L shl 8), decodedEvent(bytes(0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)))
        assertEquals(BoardEvent.BoardSnapshot(1L shl 7), decodedEvent(bytes(0x01, 0x80, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun snapshotRoundTripsRepresentativeOccupancies() {
        val patterns = listOf(0L, ALL_OCCUPIED, START_OCCUPANCY, 1L shl 0, 1L shl 63, 0x0123_4567_89AB_CDEFL)
        for (occupancy in patterns) {
            val snapshot = BoardEvent.BoardSnapshot(occupancy)
            assertEquals(snapshot, decodedEvent(BoardWireCodec.encodeEvent(snapshot)))
        }
    }

    // --- DEVICE_STATUS (§1.3, tag 0x04): battery 1B, fw 3B (major/minor/patch), uptime uint32 LE 4B ---

    @Test
    fun encodesDeviceStatusGoldenFrames() {
        // battery 100 = 0x64, fw 1.2.3, uptime 0.
        assertContentEquals(
            bytes(0x04, 0x64, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00),
            BoardWireCodec.encodeEvent(BoardEvent.DeviceStatus(100, FirmwareVersion(1, 2, 3), 0L)),
        )
        // uptime 0x04030201 = 67305985 → LE bytes 01 02 03 04 (proves byte order, not value).
        assertContentEquals(
            bytes(0x04, 0x32, 0x02, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04),
            BoardWireCodec.encodeEvent(BoardEvent.DeviceStatus(50, FirmwareVersion(2, 0, 1), 67_305_985L)),
        )
    }

    @Test
    fun decodesDeviceStatusGoldenFrames() {
        assertEquals(
            BoardEvent.DeviceStatus(100, FirmwareVersion(1, 2, 3), 0L),
            decodedEvent(bytes(0x04, 0x64, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00)),
        )
        assertEquals(
            BoardEvent.DeviceStatus(50, FirmwareVersion(2, 0, 1), 67_305_985L),
            decodedEvent(bytes(0x04, 0x32, 0x02, 0x00, 0x01, 0x01, 0x02, 0x03, 0x04)),
        )
        // uptime 0xFFFFFFFF must decode to 4_294_967_295 (uint32 held in a Long), NOT -1 — the
        // Kotlin/Native signed-shift trap this whole cross-target test guards against.
        assertEquals(
            BoardEvent.DeviceStatus(0, FirmwareVersion(0, 0, 0), 4_294_967_295L),
            decodedEvent(bytes(0x04, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)),
        )
    }

    @Test
    fun deviceStatusRoundTripsAcrossUptimeRange() {
        val uptimes = listOf(0L, 1L, 255L, 256L, 67_305_985L, 4_294_967_295L)
        for (uptime in uptimes) {
            val status = BoardEvent.DeviceStatus(73, FirmwareVersion(9, 8, 7), uptime)
            assertEquals(status, decodedEvent(BoardWireCodec.encodeEvent(status)))
        }
    }

    // --- Commands mobile → board (§1.4): SET_MODE 0x81, REQUEST_SNAPSHOT 0x82, REQUEST_STATUS 0x83 ---

    @Test
    fun encodesCommandGoldenFrames() {
        assertContentEquals(bytes(0x81, 0x00), BoardWireCodec.encodeCommand(BoardCommand.SetMode(BoardMode.GAME)))
        assertContentEquals(bytes(0x81, 0x01), BoardWireCodec.encodeCommand(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)))
        assertContentEquals(bytes(0x82), BoardWireCodec.encodeCommand(BoardCommand.RequestSnapshot))
        assertContentEquals(bytes(0x83), BoardWireCodec.encodeCommand(BoardCommand.RequestStatus))
    }

    @Test
    fun decodesCommandGoldenFrames() {
        assertEquals(BoardCommand.SetMode(BoardMode.GAME), decodedCommand(bytes(0x81, 0x00)))
        assertEquals(BoardCommand.SetMode(BoardMode.DIAGNOSTIC), decodedCommand(bytes(0x81, 0x01)))
        assertEquals(BoardCommand.RequestSnapshot, decodedCommand(bytes(0x82)))
        assertEquals(BoardCommand.RequestStatus, decodedCommand(bytes(0x83)))
    }

    @Test
    fun commandRoundTripsEveryCommand() {
        val commands =
            listOf(
                BoardCommand.SetMode(BoardMode.GAME),
                BoardCommand.SetMode(BoardMode.DIAGNOSTIC),
                BoardCommand.RequestSnapshot,
                BoardCommand.RequestStatus,
            )
        for (command in commands) {
            assertEquals(command, decodedCommand(BoardWireCodec.encodeCommand(command)))
        }
    }

    // --- Malformed frames: decoding is total, never throws; every bad frame is reported ---

    @Test
    fun reportsMalformedEvents() {
        assertEventMalformed(bytes()) // empty
        assertEventMalformed(bytes(0x05, 0x00)) // unknown event tag
        assertEventMalformed(bytes(0x01, 0xFF)) // BOARD_SNAPSHOT truncated (2 of 9 bytes)
        assertEventMalformed(bytes(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // BOARD_SNAPSHOT oversized (10 bytes)
        assertEventMalformed(bytes(0x02)) // SQUARE_EVENT missing payload
        assertEventMalformed(bytes(0x02, 0x85)) // SQUARE_EVENT reserved event bits 10
        assertEventMalformed(bytes(0x02, 0xC0)) // SQUARE_EVENT reserved event bits 11
        assertEventMalformed(bytes(0x03, 0x02)) // BUTTON_EVENT out-of-range button
        assertEventMalformed(bytes(0x04, 0x64)) // DEVICE_STATUS truncated
    }

    @Test
    fun reportsMalformedCommands() {
        assertCommandMalformed(bytes()) // empty
        assertCommandMalformed(bytes(0x84)) // reserved post-MVP command tag (0x84–0x9F)
        assertCommandMalformed(bytes(0x90)) // reserved post-MVP command tag
        assertCommandMalformed(bytes(0x81, 0x02)) // SET_MODE out-of-range mode
        assertCommandMalformed(bytes(0x81)) // SET_MODE missing payload
        assertCommandMalformed(bytes(0x82, 0x00)) // REQUEST_SNAPSHOT with stray payload
        assertCommandMalformed(bytes(0x83, 0x00)) // REQUEST_STATUS with stray payload
    }

    private fun square(
        index: Int,
        type: SquareEventType,
    ): BoardEvent.SquareEvent = BoardEvent.SquareEvent(index, type)

    private fun decodedEvent(frame: ByteArray): BoardEvent =
        when (val result = BoardWireCodec.decodeEvent(frame)) {
            is EventDecodeResult.Decoded -> result.event
            is EventDecodeResult.Malformed -> fail("expected Decoded, got Malformed: ${result.reason}")
        }

    private fun decodedCommand(frame: ByteArray): BoardCommand =
        when (val result = BoardWireCodec.decodeCommand(frame)) {
            is CommandDecodeResult.Decoded -> result.command
            is CommandDecodeResult.Malformed -> fail("expected Decoded, got Malformed: ${result.reason}")
        }

    private fun assertEventMalformed(frame: ByteArray) {
        assertTrue(
            BoardWireCodec.decodeEvent(frame) is EventDecodeResult.Malformed,
            "expected Malformed for frame of size ${frame.size}",
        )
    }

    private fun assertCommandMalformed(frame: ByteArray) {
        assertTrue(
            BoardWireCodec.decodeCommand(frame) is CommandDecodeResult.Malformed,
            "expected Malformed for frame of size ${frame.size}",
        )
    }

    private companion object {
        // 0xFFFF_FFFF_FFFF_FFFF — all 64 squares occupied.
        const val ALL_OCCUPIED = -1L

        // Ranks 1,2 (squares 0–15) and ranks 7,8 (squares 48–63) — chess start position occupancy.
        val START_OCCUPANCY = 0xFFFFL or (0xFFFFL shl 48)

        // Builds a frame from unsigned byte values (0x00–0xFF) without per-element .toByte() noise.
        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}
