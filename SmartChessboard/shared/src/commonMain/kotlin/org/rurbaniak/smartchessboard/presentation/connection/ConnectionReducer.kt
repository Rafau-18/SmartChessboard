package org.rurbaniak.smartchessboard.presentation.connection

import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard

/**
 * The pure heart of the connection screen: every `(state, message)` transition, no IO. All transport
 * driving ([org.rurbaniak.smartchessboard.domain.board.BoardTransport] scan/connect/disconnect) and
 * the remembered-board persistence are deferred to the [ConnectionEffect]s the [ConnectionViewModel]
 * interprets, so this stays exhaustively testable on every target (the shape the physical-play reducer
 * established).
 *
 * The machine: [ConnectionPhase.NeedsPermission] → (granted) either auto-connect to a remembered board
 * or [ConnectionPhase.Scanning] → (tap) [ConnectionPhase.Connecting] → [ConnectionPhase.Connected],
 * with [ConnectionPhase.PermissionDenied] / [ConnectionPhase.Failed] branches and Forget / Retry
 * recoveries. Transport-origin messages map the radio lifecycle onto these phases; the failure
 * taxonomy ([ConnectionFailure]) is best-effort and re-verified on real hardware (Phase 8).
 */
fun reduceConnection(
    state: ConnectionUiState,
    msg: ConnectionMsg,
): ConnectionReduceResult =
    when (msg) {
        ConnectionMsg.PermissionGranted -> {
            onPermissionGranted(state)
        }

        ConnectionMsg.PermissionDenied -> {
            ConnectionReduceResult(state.copy(phase = ConnectionPhase.PermissionDenied))
        }

        is ConnectionMsg.ScanResultsChanged -> {
            onScanResults(state, msg.devices)
        }

        ConnectionMsg.ScanTimedOut -> {
            // The board never appeared within the scan window (powered off / out of range / busy with
            // another central — the board is single-central). Surface a Retry-able failure instead of an
            // endless "Scanning…/Looking for your saved board…" spinner. Ignored if we already moved on.
            if (state.phase is ConnectionPhase.Scanning) {
                ConnectionReduceResult(
                    state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.OUT_OF_RANGE)),
                    listOf(ConnectionEffect.StopScan),
                )
            } else {
                ConnectionReduceResult(state)
            }
        }

        is ConnectionMsg.DeviceSelected -> {
            ConnectionReduceResult(
                state.copy(phase = ConnectionPhase.Connecting(pairing = false), connectingTo = msg.id),
                listOf(ConnectionEffect.StopScan, ConnectionEffect.Connect(msg.id)),
            )
        }

        ConnectionMsg.ForgetDevice -> {
            ConnectionReduceResult(
                state.copy(
                    phase = ConnectionPhase.Scanning,
                    rememberedBoardId = null,
                    connectingTo = null,
                    devices = emptyList(),
                ),
                // Drop any current link, clear the persisted id, then re-scan from scratch.
                listOf(ConnectionEffect.Disconnect, ConnectionEffect.ForgetBoard, ConnectionEffect.StartScan),
            )
        }

        ConnectionMsg.Retry -> {
            onRetry(state)
        }

        is ConnectionMsg.TransportStateChanged -> {
            onTransportState(state, msg.state)
        }
    }

// Permission just granted. If the singleton adapter is **already connected** — the link deliberately
// persists across screens, so re-entering this gate to resume a game finds it live — do NOT scan: a
// connected board isn't advertising, so a scan would hang forever on "Looking for your saved board…".
// The transport-driven phase is already Connected/Connecting (transportState replays its current value
// to this fresh VM), so stay there and let the screen navigate on through. (Previously the encrypted-bond
// drops kept re-advertising the board, masking this; the now-stable plaintext link exposed it — S-09 P8.)
// Otherwise scan first — even an auto-connect needs the adapter to (re)discover the advertisement before
// it can build a peripheral; onScanResults then auto-selects a remembered board the moment it reappears.
private fun onPermissionGranted(state: ConnectionUiState): ConnectionReduceResult =
    if (state.phase is ConnectionPhase.Connected || state.phase is ConnectionPhase.Connecting) {
        ConnectionReduceResult(state)
    } else {
        ConnectionReduceResult(
            state.copy(phase = ConnectionPhase.Scanning, devices = emptyList()),
            listOf(ConnectionEffect.StartScan),
        )
    }

// New scan results: refresh the list, and — when scanning for a remembered board — auto-connect the
// moment it reappears, with no tap (the "remembered-device auto-connect on entry" path).
private fun onScanResults(
    state: ConnectionUiState,
    devices: List<DiscoveredBoard>,
): ConnectionReduceResult {
    val next = state.copy(devices = devices)
    val remembered = state.rememberedBoardId
    val rememberedInRange = remembered != null && devices.any { it.id == remembered }
    return if (state.phase is ConnectionPhase.Scanning && rememberedInRange) {
        ConnectionReduceResult(
            next.copy(phase = ConnectionPhase.Connecting(pairing = false), connectingTo = remembered),
            listOf(ConnectionEffect.StopScan, ConnectionEffect.Connect(remembered)),
        )
    } else {
        ConnectionReduceResult(next)
    }
}

// Retry after a failure: re-attempt the same board if one was targeted (auto-connect / a tapped board),
// otherwise fall back to a fresh scan.
private fun onRetry(state: ConnectionUiState): ConnectionReduceResult {
    val target = state.connectingTo
    return if (target != null) {
        ConnectionReduceResult(
            state.copy(phase = ConnectionPhase.Connecting(pairing = false)),
            listOf(ConnectionEffect.Connect(target)),
        )
    } else {
        ConnectionReduceResult(
            state.copy(phase = ConnectionPhase.Scanning, devices = emptyList()),
            listOf(ConnectionEffect.StartScan),
        )
    }
}

// Map the transport radio lifecycle onto the connection phases. Only the connecting/connected
// transitions carry effects (remember the board on success); the rest are phase-only.
private fun onTransportState(
    state: ConnectionUiState,
    transport: BoardTransportState,
): ConnectionReduceResult =
    when (transport) {
        BoardTransportState.Connected -> {
            val target = state.connectingTo
            ConnectionReduceResult(
                state.copy(
                    phase = ConnectionPhase.Connected,
                    rememberedBoardId = target ?: state.rememberedBoardId,
                ),
                // Persist the board so the next entry auto-connects. Idempotent on a duplicate Connected.
                if (target != null) listOf(ConnectionEffect.RememberBoard(target)) else emptyList(),
            )
        }

        // The real Kable adapter does not currently surface a distinct Pairing state (its State maps to
        // Connecting/Connected/Disconnected); this arm keeps the machine total and lights the "Pairing…"
        // sub-label if a future adapter reports it, but only while we are actually connecting.
        BoardTransportState.Pairing -> {
            if (state.phase is ConnectionPhase.Connecting) {
                ConnectionReduceResult(state.copy(phase = ConnectionPhase.Connecting(pairing = true)))
            } else {
                ConnectionReduceResult(state)
            }
        }

        BoardTransportState.BluetoothOff -> {
            ConnectionReduceResult(state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.BLUETOOTH_OFF)))
        }

        // The transport can also report a denied permission (e.g. iOS denial surfaced at scan time).
        BoardTransportState.PermissionDenied -> {
            ConnectionReduceResult(state.copy(phase = ConnectionPhase.PermissionDenied))
        }

        BoardTransportState.OutOfRange -> {
            ConnectionReduceResult(state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.OUT_OF_RANGE)))
        }

        BoardTransportState.BondFailed -> {
            ConnectionReduceResult(state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.BOND_FAILED)))
        }

        // A disconnect while connecting is a failed attempt; after a live connection it means the link
        // dropped. Idle/Scanning disconnects (e.g. the echo of StopScan or Disconnect) are ignored.
        BoardTransportState.Disconnected -> {
            when (state.phase) {
                is ConnectionPhase.Connecting -> {
                    ConnectionReduceResult(state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.GENERIC)))
                }

                ConnectionPhase.Connected -> {
                    ConnectionReduceResult(state.copy(phase = ConnectionPhase.Failed(ConnectionFailure.OUT_OF_RANGE)))
                }

                else -> {
                    ConnectionReduceResult(state)
                }
            }
        }

        // Progress states we drive ourselves (Connecting via DeviceSelected, Scanning via StartScan) or
        // transient idle — the scan-result list and our own phase already reflect them; ignore the echo.
        BoardTransportState.Connecting, BoardTransportState.Scanning, BoardTransportState.Idle -> {
            ConnectionReduceResult(state)
        }
    }
