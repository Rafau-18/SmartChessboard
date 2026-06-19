package org.rurbaniak.smartchessboard.data.board.emulator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.data.board.protocol.BoardWireCodec
import org.rurbaniak.smartchessboard.data.board.protocol.CommandDecodeResult
import org.rurbaniak.smartchessboard.data.board.protocol.EventDecodeResult
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.FirmwareVersion
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.chess.isValidSquare
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// A no-hardware reed-switch board (F-02). It implements the domain BoardConnection port the same way
// the S-09 BLE adapter will, so a consumer (S-06) cannot tell the two apart at the event-stream
// level. It holds a 64-bit occupancy bitmap, exposes script primitives a test drives, and — crucially
// — produces every emitted event by encoding it to §1.3 bytes through the shared BoardWireCodec and
// decoding the bytes back (see [emitEvent]). That round trip is what makes the emulated stream
// byte-identical to the firmware's, which is why verification against the emulator transfers to the
// real board. Lives in commonMain since S-06 wired the first production consumer: it is the only
// BoardConnection bound on Android/iOS until the S-09 BLE adapter ships. The chess-agnostic scenario
// DSL (BoardScenarios) that drives it stays test-only in commonTest.

/** Static device facts the emulator reports in DEVICE_STATUS (§1.3); fixed for a session so the stream is deterministic. */
data class EmulatedDeviceStatus(
    val batteryPct: Int = 100,
    val firmwareVersion: FirmwareVersion = FirmwareVersion(1, 0, 0),
    val uptimeSeconds: Long = 0L,
)

/**
 * @param scope the [CoroutineScope] the time-driven jobs (10 Hz diagnostic snapshots, periodic status)
 *   run on — pass a test scope so virtual time drives them deterministically; jobs cancel on disconnect.
 * @param initialOccupancy the bitmap the board powers on with (default: the chess start layout).
 * @param status the fixed [EmulatedDeviceStatus] every DEVICE_STATUS reports.
 * @param statusInterval cadence of the periodic DEVICE_STATUS job; [Duration.INFINITE] disables it
 *   entirely, which tests asserting an exact ordered stream use so the ~30 s status never interleaves.
 * @param eventDelay optional pacing inserted before each emission (default zero — keeps the suite fast).
 */
class EmulatedBoard(
    private val scope: CoroutineScope,
    initialOccupancy: Long = STARTING_POSITION_OCCUPANCY,
    private val status: EmulatedDeviceStatus = EmulatedDeviceStatus(),
    private val statusInterval: Duration = 30.seconds,
    private val eventDelay: Duration = Duration.ZERO,
) : BoardConnection {
    private val _connectionState = MutableStateFlow(BoardConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BoardConnectionState> = _connectionState.asStateFlow()

    // replay = 0 honours the port's "hot, no-replay" contract (late subscribers miss earlier events);
    // the small extra buffer decouples a producing job from a momentarily-busy consumer so neither the
    // diagnostic loop nor a connect burst deadlocks waiting on collection.
    private val _events = MutableSharedFlow<BoardEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<BoardEvent> = _events.asSharedFlow()

    /** Current 64-bit occupancy (bit N = square N occupied, a1 = bit 0). Exposed for assertions and dev tooling. */
    var occupancy: Long = initialOccupancy
        private set

    private var mode: BoardMode = BoardMode.GAME
    private var statusJob: Job? = null
    private var diagnosticJob: Job? = null

    private val isConnected: Boolean get() = _connectionState.value == BoardConnectionState.CONNECTED

    // --- Driver surface (not on the port): how a script or dev tool drives the board ---

    /** Bring the link up: emit the snapshot then status burst (§1.3 on-connect), reset to GAME mode (§1.7), start periodic status. */
    suspend fun connect() {
        _connectionState.value = BoardConnectionState.CONNECTED
        // §1.7: every (re)connect resets the board to GAME; the mobile re-enters diagnostic explicitly.
        mode = BoardMode.GAME
        stopDiagnosticJob()
        emitEvent(snapshotEvent())
        emitEvent(statusEvent())
        startStatusJob()
    }

    /** Drop the link: no event is emitted (a dead link delivers nothing) and the time-driven jobs stop. */
    fun disconnect() {
        _connectionState.value = BoardConnectionState.DISCONNECTED
        stopDiagnosticJob()
        stopStatusJob()
    }

    /**
     * Lift the piece on [square]. Guard: the square must be occupied — a real board cannot report a lift
     * from an empty square, so a script that tries is a bug and throws loudly. While disconnected the
     * occupancy still changes but nothing is emitted; the divergence surfaces only in the reconnect snapshot.
     */
    suspend fun lift(square: Int) {
        require(isValidSquare(square)) { "square must be in 0..63, was $square" }
        check(isOccupied(square)) { "cannot lift from empty square $square — a real board never reports this" }
        occupancy = occupancy and (1L shl square).inv()
        if (isConnected) emitEvent(BoardEvent.SquareEvent(square, SquareEventType.LIFT))
    }

    /**
     * Place a piece on [square]. Guard: the square must be empty — a real board cannot report a place
     * onto an occupied square. Offline placements mutate occupancy silently, like [lift].
     */
    suspend fun place(square: Int) {
        require(isValidSquare(square)) { "square must be in 0..63, was $square" }
        check(!isOccupied(square)) { "cannot place on occupied square $square — a real board never reports this" }
        occupancy = occupancy or (1L shl square)
        if (isConnected) emitEvent(BoardEvent.SquareEvent(square, SquareEventType.PLACE))
    }

    /** Press a confirm button. While disconnected this is a silent no-op — a lost press buffers nowhere. */
    suspend fun pressButton(button: BoardButton) {
        if (!isConnected) return
        emitEvent(BoardEvent.ButtonEvent(button))
    }

    /**
     * Overwrite occupancy wholesale — allowed only while disconnected, to stage an offline board change
     * the reconnect snapshot then reveals. While connected, occupancy may change only through lift/place,
     * so anything else would silently desync the event stream; doing so throws.
     */
    fun setOccupancy(value: Long) {
        check(!isConnected) {
            "setOccupancy is only allowed while disconnected; while connected, occupancy may change " +
                "only through lift/place so the event stream never silently desyncs"
        }
        occupancy = value
    }

    // --- Port surface: what a consumer (S-06) / the BLE adapter (S-09) talk to ---

    override suspend fun send(command: BoardCommand) {
        check(isConnected) { "cannot send $command to a disconnected board" }
        // Symmetric to [emitEvent]: react to the decoded §1.4 bytes, never to the in-memory object, so
        // the emulator exercises the same decode path the firmware will.
        val frame = BoardWireCodec.encodeCommand(command)
        val decoded =
            when (val result = BoardWireCodec.decodeCommand(frame)) {
                is CommandDecodeResult.Decoded -> {
                    result.command
                }

                is CommandDecodeResult.Malformed -> {
                    error("emulator produced a command frame its own codec rejects (${result.reason}); this is a bug")
                }
            }
        when (decoded) {
            is BoardCommand.SetMode -> applyMode(decoded.mode)
            BoardCommand.RequestSnapshot -> emitEvent(snapshotEvent())
            BoardCommand.RequestStatus -> emitEvent(statusEvent())
        }
    }

    // --- Internals ---

    private fun applyMode(newMode: BoardMode) {
        if (newMode == mode) return
        mode = newMode
        when (newMode) {
            // §1.6: diagnostic mode adds ~10 Hz snapshots on top of the normal square events.
            BoardMode.DIAGNOSTIC -> startDiagnosticJob()

            BoardMode.GAME -> stopDiagnosticJob()
        }
    }

    /**
     * The load-bearing fidelity mechanism: every event a consumer observes has made the round trip
     * typed → §1.3 bytes → typed through the shared codec, so the emulated stream is byte-identical to
     * the firmware's. A Malformed result here means the emulator built a frame its own codec rejects — a
     * bug, never a normal outcome — so it throws rather than emitting something the wire can't carry.
     */
    private suspend fun emitEvent(event: BoardEvent) {
        val frame = BoardWireCodec.encodeEvent(event)
        val decoded =
            when (val result = BoardWireCodec.decodeEvent(frame)) {
                is EventDecodeResult.Decoded -> {
                    result.event
                }

                is EventDecodeResult.Malformed -> {
                    error("emulator produced a frame its own codec rejects (${result.reason}); this is a bug")
                }
            }
        if (eventDelay > Duration.ZERO) delay(eventDelay)
        _events.emit(decoded)
    }

    private fun startStatusJob() {
        statusJob?.cancel()
        if (statusInterval == Duration.INFINITE) {
            statusJob = null
            return
        }
        statusJob =
            scope.launch {
                while (isActive) {
                    delay(statusInterval)
                    emitEvent(statusEvent())
                }
            }
    }

    private fun stopStatusJob() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun startDiagnosticJob() {
        diagnosticJob?.cancel()
        diagnosticJob =
            scope.launch {
                while (isActive) {
                    delay(DIAGNOSTIC_SNAPSHOT_INTERVAL)
                    emitEvent(snapshotEvent())
                }
            }
    }

    private fun stopDiagnosticJob() {
        diagnosticJob?.cancel()
        diagnosticJob = null
    }

    private fun isOccupied(square: Int): Boolean = (occupancy ushr square) and 1L == 1L

    private fun snapshotEvent(): BoardEvent.BoardSnapshot = BoardEvent.BoardSnapshot(occupancy)

    private fun statusEvent(): BoardEvent.DeviceStatus =
        BoardEvent.DeviceStatus(status.batteryPct, status.firmwareVersion, status.uptimeSeconds)

    companion object {
        /** §1.6 diagnostic mode streams snapshots at ~10 Hz. */
        val DIAGNOSTIC_SNAPSHOT_INTERVAL: Duration = 100.milliseconds

        /** Ranks 1, 2, 7, 8 occupied (squares 0–15 and 48–63) — the chess start layout as a pure bit pattern. */
        val STARTING_POSITION_OCCUPANCY: Long = 0xFFFFL or (0xFFFFL shl 48)
    }
}
