package org.rurbaniak.smartchessboard.data.board.ble

import org.rurbaniak.smartchessboard.data.board.protocol.BoardWireCodec
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.FirmwareVersion
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Unit coverage for the pure adapter seam (BleMapping) — the part of the S-09 BLE adapter that needs
// no radio and therefore must pass on BOTH the JVM host and a Native target (lessons.md: a commonMain
// mapping is not green until it passes on iosSimulatorArm64Test). The real Kable transport is proven
// only on the Phase 8 hardware gate; everything portable about the adapter is proven here.
class BleMappingTest {
    // --- mapNotification: a board_event notification decodes to the same typed event the board sent ---
    // Bytes come from BoardWireCodec.encodeEvent (the board / emulator side), so a green round trip proves
    // the adapter's RX path reconstructs exactly what the firmware twin emits (emulator-parity at the seam).

    @Test
    fun mapNotificationDecodesSnapshotIncludingH8SignBit() {
        // h8 = bit 63 sets Long's sign bit; the codec must round-trip it (Occupancy.kt sign-bit warning).
        val occupancy = (1L shl 63) or (1L shl 0) or 0xFFFFL
        val event = BoardEvent.BoardSnapshot(occupancy)
        assertEquals(event, BleMapping.mapNotification(BoardWireCodec.encodeEvent(event)))
    }

    @Test
    fun mapNotificationDecodesSquareEvents() {
        for (square in intArrayOf(0, 12, 28, 63)) {
            for (type in SquareEventType.entries) {
                val event = BoardEvent.SquareEvent(square, type)
                assertEquals(event, BleMapping.mapNotification(BoardWireCodec.encodeEvent(event)))
            }
        }
    }

    @Test
    fun mapNotificationDecodesButtonAndStatus() {
        val white = BoardEvent.ButtonEvent(BoardButton.WHITE)
        val black = BoardEvent.ButtonEvent(BoardButton.BLACK)
        assertEquals(white, BleMapping.mapNotification(BoardWireCodec.encodeEvent(white)))
        assertEquals(black, BleMapping.mapNotification(BoardWireCodec.encodeEvent(black)))
        // uptime > 2^31 exercises the unsigned-32-bit field held in a Long (no sign confusion).
        val status = BoardEvent.DeviceStatus(100, FirmwareVersion(1, 0, 0), uptimeSeconds = 4_000_000_000L)
        assertEquals(status, BleMapping.mapNotification(BoardWireCodec.encodeEvent(status)))
    }

    // --- mapNotification: a malformed / garbage frame is dropped (null), never thrown — the link survives ---

    @Test
    fun mapNotificationDropsMalformedFrames() {
        assertNull(BleMapping.mapNotification(byteArrayOf())) // empty frame
        assertNull(BleMapping.mapNotification(frame(0x84))) // reserved / unknown event tag
        assertNull(BleMapping.mapNotification(frame(0x01, 0x00, 0x00))) // truncated BOARD_SNAPSHOT
        assertNull(BleMapping.mapNotification(frame(0x02, 0x80))) // SQUARE_EVENT reserved event bits
    }

    // --- connectionStateFor: only Connected is CONNECTED; every other transport state is DISCONNECTED ---

    @Test
    fun connectionStateForMapsOnlyConnectedToConnected() {
        assertEquals(
            BoardConnectionState.CONNECTED,
            BleMapping.connectionStateFor(BoardTransportState.Connected),
        )
        for (state in BoardTransportState.entries) {
            if (state == BoardTransportState.Connected) continue
            assertEquals(
                BoardConnectionState.DISCONNECTED,
                BleMapping.connectionStateFor(state),
                "expected $state to map to DISCONNECTED",
            )
        }
    }

    private fun frame(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
}
