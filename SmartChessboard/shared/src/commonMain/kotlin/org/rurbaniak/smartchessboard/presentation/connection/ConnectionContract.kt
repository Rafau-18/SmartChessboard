package org.rurbaniak.smartchessboard.presentation.connection

import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard

/**
 * MVI contract for the BLE connection / pairing screen (S-09 Phase 5). MVI is justified here per
 * `lessons.md` ("MVI only for genuinely event-heavy screens, with written justification"): the screen
 * merges three asynchronous inbound streams — the OS permission result, the transport lifecycle
 * ([BoardTransportState]), and the rolling scan-result list — into one state machine (request
 * permission → scan → select → pair → connected, with a full failure taxonomy and a remembered-device
 * auto-connect shortcut). Funnelling every transition through one [ConnectionMsg] and one pure
 * [reduceConnection] keeps that machine exhaustively testable, the same shape the physical-play core
 * uses. The simpler form/list screens (New-game, History) stay MVVM.
 *
 * Like [org.rurbaniak.smartchessboard.presentation.physical.PhysicalPlayState], the single sealed UI
 * state is also the reducer's state — there is no separate internal type to map, so the reducer output
 * is rendered directly. All IO ([BoardTransport] scan/connect, the remembered-board persistence) lives
 * in the [ConnectionEffect]s the [ConnectionViewModel] interprets, so [reduceConnection] stays pure.
 */
data class ConnectionUiState(
    /** The live point in the connect machine — drives which surface the screen renders. */
    val phase: ConnectionPhase,
    /** Boards seen in the current scan (name + RSSI), most-recently-rebuilt; empty until one is found. */
    val devices: List<DiscoveredBoard> = emptyList(),
    /** The persisted board id (if any) — non-null enables the "Forget device" affordance + auto-connect. */
    val rememberedBoardId: String? = null,
    /** The board id of the in-flight connect attempt; remembered on success, retried on failure. */
    val connectingTo: String? = null,
) {
    /** The connected board id, for the screen's one-shot navigation into physical play; null until connected. */
    val connectedBoardId: String? get() = if (phase is ConnectionPhase.Connected) connectingTo else null
}

/** The connection state machine's discrete stages; cross-cutting data lives on [ConnectionUiState]. */
sealed interface ConnectionPhase {
    /** Permission not yet decided — the screen requests it on entry, before any scan. */
    data object NeedsPermission : ConnectionPhase

    /** The user denied BLE permission — show the rationale + an "open settings" / retry escape hatch. */
    data object PermissionDenied : ConnectionPhase

    /** Scanning for boards advertising the §1.2 service UUID; results land in [ConnectionUiState.devices]. */
    data object Scanning : ConnectionPhase

    /** Connecting to / bonding with the selected board. [pairing] is true once the OS pairing step begins. */
    data class Connecting(
        val pairing: Boolean,
    ) : ConnectionPhase

    /** The link is up and (per Phase 2) encrypted; the screen navigates on into physical play. */
    data object Connected : ConnectionPhase

    /** A connect attempt failed; [reason] picks the message, and Retry / Forget are offered. */
    data class Failed(
        val reason: ConnectionFailure,
    ) : ConnectionPhase
}

/** Why a connect attempt failed — distinct shapes so the screen can give a specific, actionable message. */
enum class ConnectionFailure {
    /** Bluetooth is off at the OS level — prompt the user to enable it. */
    BLUETOOTH_OFF,

    /** The board was unreachable (out of range / powered off / advertising stopped). */
    OUT_OF_RANGE,

    /** The link came up but bonding/encryption failed (Phase 2 requires an encrypted bond). */
    BOND_FAILED,

    /** Any other connect failure (e.g. the link dropped mid-connect). */
    GENERIC,
}

/** Everything that can drive the connection state — permission-origin, transport-origin, and user-origin. */
sealed interface ConnectionMsg {
    // --- permission-origin (from the BlePermissionController the screen drives) ---
    data object PermissionGranted : ConnectionMsg

    data object PermissionDenied : ConnectionMsg

    // --- transport-origin (collected from BoardTransport by the ViewModel) ---
    data class TransportStateChanged(
        val state: BoardTransportState,
    ) : ConnectionMsg

    data class ScanResultsChanged(
        val devices: List<DiscoveredBoard>,
    ) : ConnectionMsg

    // --- user-origin ---

    /** The user tapped a board in the scan list. */
    data class DeviceSelected(
        val id: String,
    ) : ConnectionMsg

    /** The user chose "Forget device": drop the link, clear the remembered id, and re-scan. */
    data object ForgetDevice : ConnectionMsg

    /** The user tapped Retry after a failure — re-attempt the same board, or re-scan if none was targeted. */
    data object Retry : ConnectionMsg
}

/** The IO the reducer may request; the [ConnectionViewModel] is the only interpreter (keeps [reduceConnection] pure). */
sealed interface ConnectionEffect {
    data object StartScan : ConnectionEffect

    data object StopScan : ConnectionEffect

    data class Connect(
        val id: String,
    ) : ConnectionEffect

    data object Disconnect : ConnectionEffect

    /** Persist [id] as the remembered board after a successful connect. */
    data class RememberBoard(
        val id: String,
    ) : ConnectionEffect

    /** Clear the remembered board (the "forget device" path). */
    data object ForgetBoard : ConnectionEffect
}

/** The result of one [reduceConnection] step: the next state plus any effects the ViewModel must run. */
data class ConnectionReduceResult(
    val state: ConnectionUiState,
    val effects: List<ConnectionEffect> = emptyList(),
)
