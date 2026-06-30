package org.rurbaniak.smartchessboard.data.board.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.data.board.protocol.BoardWireCodec
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard

/**
 * The no-Kable half of the S-09 BLE adapter: the [BoardConnection] + [BoardTransport] flow plumbing,
 * the encode-and-guard for [send], and the notify→event funnel — all identical on Android and iOS and
 * needing no radio. The platform `KableBoardAdapter` subclasses supply the Kable lifecycle
 * (scan/connect/observe/write/disconnect) by overriding [writeCommandFrame] plus the [BoardTransport]
 * methods, and feed raw notify bytes and transport-state transitions back in through the protected
 * helpers. Mirrors how the emulator pairs a port impl with a driver surface, but split across the
 * commonMain/platform line because Kable is mobile-only — never wasmJs (web is digital-only, lessons.md).
 *
 * Constructed idle: nothing scans or connects until a caller drives [BoardTransport]; the field flows
 * sit at [BoardTransportState.Idle] / DISCONNECTED until then.
 */
abstract class BleBoardAdapterCore(
    protected val scope: CoroutineScope,
) : BoardConnection,
    BoardTransport {
    // replay = 0 honours the port's hot, no-replay contract (a late subscriber misses earlier events);
    // the small extra buffer decouples the notify producer from a momentarily-busy consumer.
    private val mutableEvents = MutableSharedFlow<BoardEvent>(replay = 0, extraBufferCapacity = 64)
    final override val events: SharedFlow<BoardEvent> = mutableEvents.asSharedFlow()

    private val mutableTransportState = MutableStateFlow(BoardTransportState.Idle)
    final override val transportState: StateFlow<BoardTransportState> = mutableTransportState.asStateFlow()

    private val mutableScanResults = MutableStateFlow<List<DiscoveredBoard>>(emptyList())
    final override val scanResults: StateFlow<List<DiscoveredBoard>> = mutableScanResults.asStateFlow()

    // The port's two-state liveness, derived from the richer transport state through the tested seam.
    final override val connectionState: StateFlow<BoardConnectionState> =
        mutableTransportState
            .map(BleMapping::connectionStateFor)
            .stateIn(scope, SharingStarted.Eagerly, BoardConnectionState.DISCONNECTED)

    final override suspend fun send(command: BoardCommand) {
        // Port contract: a dead link cannot be written to. Guard on the transport state directly — not
        // the derived connectionState flow, which lags by a coroutine hop — so the throw is exact.
        check(BleMapping.connectionStateFor(mutableTransportState.value) == BoardConnectionState.CONNECTED) {
            "cannot send $command to a disconnected board"
        }
        writeCommandFrame(BoardWireCodec.encodeCommand(command))
    }

    /** Map a raw `board_event` notification through the tested seam and emit it, dropping malformed frames. */
    protected suspend fun emitNotification(bytes: ByteArray) {
        BleMapping.mapNotification(bytes)?.let { mutableEvents.emit(it) }
    }

    /** Publish the latest discovered-board list (the platform scan collector calls this). */
    protected fun publishScanResults(boards: List<DiscoveredBoard>) {
        mutableScanResults.value = boards
    }

    /** Drive the transport lifecycle state (the platform connect/scan/state collectors call this). */
    protected fun setTransportState(state: BoardTransportState) {
        mutableTransportState.value = state
    }

    /** Platform write of an already-encoded §1.4 frame to the encryption-gated `mobile_command` characteristic. */
    protected abstract suspend fun writeCommandFrame(frame: ByteArray)

    /**
     * Non-suspend DI teardown hook (Koin `onClose`, Phase 4) — the prescribed `TODO(S-09)` leak fix.
     * Unlike the emulator (a connect-on-bind, process-lifetime singleton whose scope was never
     * cancelled), the BLE adapter must drop the link, release the radio, and stop every collector when
     * the Koin graph closes. `onClose` cannot suspend, so the suspend [disconnect] is launched on the
     * adapter's own [scope], which is then cancelled in a `finally` so no scan/observe/state collector
     * (including the eager [connectionState] `stateIn`) outlives the graph.
     */
    fun close() {
        scope.launch {
            try {
                stopScan()
                disconnect()
            } finally {
                scope.cancel()
            }
        }
    }
}
