package org.rurbaniak.smartchessboard.domain.board

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The transport-lifecycle surface that [BoardConnection] deliberately omits — scanning, connecting to
 * a chosen device, disconnecting — plus a discovered-device stream and a richer, UX-facing transport
 * state. The connection screen (commonMain MVI, S-09 Phase 5) drives this without depending on the
 * concrete Kable adapter; the same adapter object implements both this and [BoardConnection], the way
 * [org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard] pairs its port impl with a driver
 * surface. Kept distinct from the port's two-state [BoardConnectionState] so the screen can show
 * "scanning" / "pairing" / "out of range" without leaking those into the move-acceptance gate.
 *
 * Mobile-only by construction: the only implementation is the Kable adapter in androidMain/iosMain;
 * the web target has no board transport (web is digital-only — see lessons.md).
 */
interface BoardTransport {
    /** Richer transport lifecycle (scan / connect / pair / fail), distinct from the port's CONNECTED/DISCONNECTED. */
    val transportState: StateFlow<BoardTransportState>

    /** Latest snapshot of boards discovered in the current scan; empty until [startScan] finds one. */
    val scanResults: Flow<List<DiscoveredBoard>>

    /** Begin scanning for boards advertising the §1.2 service UUID. Idempotent; results land in [scanResults]. */
    fun startScan()

    /** Stop an in-flight scan. Idempotent. */
    fun stopScan()

    /**
     * Connect (and bond, via the encryption-required characteristic — §1.1/§1.2) to the board whose
     * [DiscoveredBoard.id] equals [id]. Suspends until the link is up or the attempt fails.
     */
    suspend fun connect(id: String)

    /**
     * Re-establish the link to the most recently connected board without a fresh scan (S-09 Phase 8):
     * the manual "Reconnect" affordance on the physical screen and the immediate retry the adapter's
     * foreground auto-reconnect rides on. No-op if no board has been connected this session. Idempotent
     * — calling it while already connected or mid-attempt does nothing harmful.
     */
    suspend fun reconnect()

    /** Drop the link and release the radio. Idempotent. */
    suspend fun disconnect()
}

/** A board seen during a scan — the minimum the connection list renders and [BoardTransport.connect] needs. */
data class DiscoveredBoard(
    val id: String,
    val name: String?,
    val rssi: Int?,
)

/**
 * The transport lifecycle as the connection screen sees it. Richer than [BoardConnectionState] (which
 * is only CONNECTED/DISCONNECTED) so the UI can distinguish progress (Scanning/Connecting/Pairing)
 * from failures (Bluetooth off, permission denied, out of range, bond failed). Only [Connected] maps
 * to the port's CONNECTED — see [org.rurbaniak.smartchessboard.data.board.ble.BleMapping].
 */
enum class BoardTransportState {
    Idle,
    Scanning,
    Connecting,
    Pairing,
    Connected,
    Disconnected,
    BluetoothOff,
    PermissionDenied,
    OutOfRange,
    BondFailed,
}
