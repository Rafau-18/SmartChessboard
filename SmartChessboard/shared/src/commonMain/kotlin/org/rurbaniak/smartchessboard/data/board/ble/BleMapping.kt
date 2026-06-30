package org.rurbaniak.smartchessboard.data.board.ble

import org.rurbaniak.smartchessboard.data.board.protocol.BoardWireCodec
import org.rurbaniak.smartchessboard.data.board.protocol.EventDecodeResult
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState

// The transport-agnostic core of the S-09 BLE adapter: the two pieces of adapter logic that need no
// Kable and therefore live in commonMain, fully unit-testable on every target (JVM + Native) even
// though the real radio runs only on a device. This is the "keep Kable at the very edge" discipline —
// the platform KableBoardAdapter does nothing but funnel Kable's notify bytes and connection state
// through these functions, so the part of the adapter that can be wrong in a portable way is exactly
// the part a test can reach. (A commonMain mapping is not green until it passes on a Native target —
// lessons.md; these are covered on both testAndroidHostTest and iosSimulatorArm64Test.)

/** Pure mapping/state seam shared by the Android and iOS Kable adapters; no Kable, no I/O, no state. */
object BleMapping {
    /**
     * Decode one `board_event` notification (§1.3) into a typed [BoardEvent], dropping a malformed
     * frame (truncated notification, reserved tag) by returning null rather than throwing — the
     * adapter must stay live across a garbage frame, never tear the link down. The codec here is the
     * same one the firmware twin and the emulator use, so this RX path inherits their byte fidelity.
     */
    fun mapNotification(bytes: ByteArray): BoardEvent? =
        when (val result = BoardWireCodec.decodeEvent(bytes)) {
            is EventDecodeResult.Decoded -> result.event
            is EventDecodeResult.Malformed -> null
        }

    /**
     * Collapse the richer [BoardTransportState] down to the port's two-state view: only [Connected]
     * is CONNECTED; every other state (scanning, connecting, pairing, any failure, disconnected) maps
     * to DISCONNECTED, because a consumer above the port may write or accept a move only on a live
     * link. This is the one place that decision lives, so the derived `connectionState` flow and the
     * `send()` guard cannot disagree.
     */
    fun connectionStateFor(transportState: BoardTransportState): BoardConnectionState =
        when (transportState) {
            BoardTransportState.Connected -> BoardConnectionState.CONNECTED
            else -> BoardConnectionState.DISCONNECTED
        }
}
