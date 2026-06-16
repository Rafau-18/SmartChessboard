package org.rurbaniak.smartchessboard.domain.board

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam consumers depend on to talk to a reed-switch board, regardless of transport.
 * The emulator implements it now (F-02); the BLE adapter re-implements it over Kable in S-09. It
 * deliberately models only what a connected consumer sees — connected/disconnected and the event
 * stream — not transport lifecycle (scanning, pairing, MTU), which belongs to the concrete adapter.
 *
 * Usage contract:
 * - [events] is a hot, no-replay flow. Subscribe **before** driving or connecting the board,
 *   otherwise the snapshot-and-status burst emitted on connect (§1.3) is missed.
 * - [send] writes a [BoardCommand] to the board (§1.4) and throws [IllegalStateException] when the
 *   connection is disconnected — the mobile cannot write to a dead link.
 */
interface BoardConnection {
    /** Whether the board is currently reachable. Snapshot-on-(re)connect rides on the transition to CONNECTED (§1.7). */
    val connectionState: StateFlow<BoardConnectionState>

    /** Hot stream of board → mobile events (§1.3). No replay: late subscribers miss earlier events. */
    val events: SharedFlow<BoardEvent>

    /** Sends a command to the board (§1.4). Throws [IllegalStateException] if disconnected. */
    suspend fun send(command: BoardCommand)
}

/** Connection liveness as seen by a consumer; transport-specific states (scanning, pairing) are not modelled here. */
enum class BoardConnectionState {
    CONNECTED,
    DISCONNECTED,
}
