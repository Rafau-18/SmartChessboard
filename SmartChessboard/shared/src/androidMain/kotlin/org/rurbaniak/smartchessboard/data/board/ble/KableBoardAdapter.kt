package org.rurbaniak.smartchessboard.data.board.ble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard
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

    override fun startScan() {
        if (scanJob != null) return
        discovered.value = emptyMap()
        publishScanResults(emptyList())
        setTransportState(BoardTransportState.Scanning)
        scanJob =
            scope.launch {
                scanner.advertisements.collect { advertisement ->
                    // Kable's identifier is already a String on Android (it is a Uuid on iOS — the one
                    // spot the two adapters diverge); key the cache and DiscoveredBoard.id by it so
                    // connect(id) matches.
                    discovered.value = discovered.value + (advertisement.identifier to advertisement)
                    publishScanResults(discovered.value.values.map { it.toDiscoveredBoard() })
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
        val target = Peripheral(advertisement)
        peripheral = target
        // Mirror Kable's connection state into the transport state for the whole connection lifetime.
        stateJob = target.state.onEach { setTransportState(it.toTransportState()) }.launchIn(scope)
        // observe() enables the board_event CCCD — triggering the firmware on-subscribe burst and, via
        // the encryption-required path (Phase 2), the OS pairing/bond — and auto-resubscribes across a
        // reconnect (§1.7). Each notify funnels through the tested seam. Launched before connect() so
        // the burst is never missed.
        observeJob = target.observe(boardEvent).onEach { emitNotification(it) }.launchIn(scope)
        setTransportState(BoardTransportState.Connecting)
        target.connect()
    }

    override suspend fun disconnect() {
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
