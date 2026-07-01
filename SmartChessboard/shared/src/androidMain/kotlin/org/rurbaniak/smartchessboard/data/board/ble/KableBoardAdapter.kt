package org.rurbaniak.smartchessboard.data.board.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * The real [org.rurbaniak.smartchessboard.domain.board.BoardConnection] +
 * [org.rurbaniak.smartchessboard.domain.board.BoardTransport] over Kable (S-09), Android half. Kept
 * byte-for-byte parallel to the iOS adapter: Kable's API is common, but Kable is bound only in
 * androidMain/iosMain (never wasmJs — web is digital-only, lessons.md) and this project has no shared
 * mobile source set, so the thin Kable wiring is duplicated per platform while every no-Kable concern
 * lives in [BleBoardAdapterCore]. Constructed idle — nothing scans or connects until the connection
 * screen drives it (Phase 5); the emulator-style connect-on-bind that leaked the link is gone (Phase 4 DI).
 */
class KableBoardAdapter(
    scope: CoroutineScope,
) : BleBoardAdapterCore(scope) {
    // Kable 0.43 takes kotlin.uuid.Uuid (the String characteristicOf overloads are deprecated-to-error);
    // parse the frozen §1.2 string constants once.
    private val serviceUuid = Uuid.parse(BleUuids.SERVICE)
    private val boardEvent = characteristicOf(serviceUuid, Uuid.parse(BleUuids.BOARD_EVENT))
    private val mobileCommand = characteristicOf(serviceUuid, Uuid.parse(BleUuids.MOBILE_COMMAND))

    private val scanner =
        Scanner {
            filters {
                // The §1.2 service UUID rides in the advertisement (the name is in the scan response),
                // so filtering on it is the reliable discriminator; the name is for display only.
                match { services = listOf(serviceUuid) }
            }
        }

    // Advertisements seen this scan, keyed by Kable identifier, so connect(id) can rebuild a Peripheral
    // without a second scan round-trip. Single-writer (the scan collector); a StateFlow keeps the
    // cross-thread read in connect() race-free under the Native memory model.
    private val discovered = MutableStateFlow<Map<String, Advertisement>>(emptyMap())

    private var scanJob: Job? = null
    private var peripheral: Peripheral? = null
    private var stateJob: Job? = null
    private var observeJob: Job? = null
    private var reconnectJob: Job? = null

    // Tells an unexpected link drop (auto-reconnect) apart from a user-driven disconnect() (stay down).
    private var userDisconnect = false

    // Gates auto-reconnect to a *drop* (a Disconnected after a real Connected): a freshly built Peripheral
    // starts Disconnected, so without this the collector's first emission would race the initial connect().
    private var connectedOnce = false

    override fun startScan() {
        if (scanJob?.isActive == true) return
        discovered.value = emptyMap()
        publishScanResults(emptyList())
        setTransportState(BoardTransportState.Scanning)
        scanJob =
            scope.launch {
                try {
                    scanner.advertisements.collect { advertisement ->
                        // Kable's identifier is already a String on Android (it is a Uuid on iOS — the one
                        // spot the two adapters diverge); key the cache and DiscoveredBoard.id by it so
                        // connect(id) matches.
                        discovered.value = discovered.value + (advertisement.identifier to advertisement)
                        publishScanResults(discovered.value.values.map { it.toDiscoveredBoard() })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Scanning with Bluetooth off (or the radio otherwise unavailable) throws — surface a
                    // BluetoothOff failure instead of an uncaught exception that crashes the app (S-09 P8).
                    setTransportState(BoardTransportState.BluetoothOff)
                }
            }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (transportState.value == BoardTransportState.Scanning) {
            setTransportState(BoardTransportState.Idle)
        }
    }

    override suspend fun connect(id: String) {
        val advertisement =
            discovered.value[id] ?: error("unknown board id '$id' — startScan() must surface it first")
        stopScan()
        userDisconnect = false
        connectedOnce = false
        val target = Peripheral(advertisement)
        peripheral = target
        // observe() enables the board_event CCCD — triggering the firmware on-subscribe burst and, via
        // the encryption-required path (Phase 2), the OS pairing/bond — and auto-resubscribes across a
        // reconnect (§1.7). Each notify funnels through the tested seam. Launched before connect() so
        // the burst is never missed.
        observeJob = target.observe(boardEvent).onEach { emitNotification(it) }.launchIn(scope)
        setTransportState(BoardTransportState.Connecting)
        // First-pair tolerance (S-09 Phase 8 fix): the OS pairing dialog adds latency and Android often
        // drops the link once during bonding, so connect() can throw on the first attempt and succeed on
        // the next once the bond is cached. Retry here, and DON'T mirror Kable's transient Disconnected to
        // the transport state until we're genuinely connected — otherwise the screen flashes "failed"
        // while the pairing dialog is still up (forcing a manual Retry).
        // Overall timeout so a stuck or bond-desynced connect can't hang the screen on "Connecting…"
        // forever — it falls through to a Retry-able failure instead (S-09 Phase 8).
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { attemptConnect(target) } ?: false
        if (!connected) {
            if (peripheral === target) setTransportState(BoardTransportState.OutOfRange)
            return
        }
        connectedOnce = true
        setTransportState(BoardTransportState.Connected)
        // Now mirror the ongoing Kable state for the rest of the lifetime: surface a real drop and ride it
        // into the foreground auto-reconnect (observe() re-subscribes but never *initiates* a reconnect).
        stateJob =
            target.state
                .onEach { state ->
                    setTransportState(state.toTransportState())
                    if (state is State.Connected) connectedOnce = true
                    if (state is State.Disconnected && connectedOnce && !userDisconnect && peripheral === target) {
                        scheduleReconnect(target)
                    }
                }.launchIn(scope)
    }

    // Bounded initial-connect retry that rides over the first-pair bonding drop. Silent on the transport
    // state (the caller holds it at Connecting) so a transient failure never surfaces as Failed.
    private suspend fun attemptConnect(target: Peripheral): Boolean {
        var delayMs = RECONNECT_INITIAL_DELAY_MS
        repeat(CONNECT_MAX_ATTEMPTS) {
            if (userDisconnect || peripheral !== target) return false
            try {
                target.connect()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Transient first-pair / GATT error — retry once the bond caches.
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
        return false
    }

    override suspend fun reconnect() {
        val target = peripheral ?: return
        userDisconnect = false
        // A manual press supersedes any backed-off loop with an immediate attempt.
        reconnectJob?.cancel()
        reconnectJob = null
        scheduleReconnect(target)
    }

    // Bounded foreground retry on the same Peripheral: the observe()/state collectors stay live across a
    // drop, so re-calling connect() reconnects and re-subscribes. Stops on success, a user disconnect, a
    // target swap, or attempt exhaustion (the manual Reconnect button restarts it).
    private fun scheduleReconnect(target: Peripheral) {
        if (reconnectJob?.isActive == true) return
        reconnectJob =
            scope.launch {
                var delayMs = RECONNECT_INITIAL_DELAY_MS
                repeat(RECONNECT_MAX_ATTEMPTS) {
                    if (userDisconnect || peripheral !== target) return@launch
                    if (target.state.value is State.Connected) return@launch
                    try {
                        setTransportState(BoardTransportState.Connecting)
                        target.connect()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        setTransportState(BoardTransportState.Disconnected)
                    }
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                }
            }
    }

    override suspend fun disconnect() {
        userDisconnect = true
        connectedOnce = false
        reconnectJob?.cancel()
        reconnectJob = null
        observeJob?.cancel()
        observeJob = null
        stateJob?.cancel()
        stateJob = null
        peripheral?.disconnect()
        peripheral = null
        setTransportState(BoardTransportState.Disconnected)
    }

    override suspend fun writeCommandFrame(frame: ByteArray) {
        val target = peripheral ?: error("cannot write to a board that was never connected")
        // WithResponse: the encryption-gated write is the load-bearing bond trigger (Phase 2), and the
        // response confirms the board accepted the §1.4 command.
        target.write(mobileCommand, frame, WriteType.WithResponse)
    }
}

private fun Advertisement.toDiscoveredBoard(): DiscoveredBoard =
    DiscoveredBoard(id = identifier, name = name ?: peripheralName, rssi = rssi)

private fun State.toTransportState(): BoardTransportState =
    when (this) {
        is State.Connected -> BoardTransportState.Connected
        is State.Connecting -> BoardTransportState.Connecting
        State.Disconnecting -> BoardTransportState.Disconnected
        is State.Disconnected -> BoardTransportState.Disconnected
    }
