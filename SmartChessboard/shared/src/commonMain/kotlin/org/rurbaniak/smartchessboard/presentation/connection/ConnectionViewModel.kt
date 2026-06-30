package org.rurbaniak.smartchessboard.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.RememberedBoardStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * The impure shell around the pure [reduceConnection]. It owns what the reducer cannot: it collects the
 * transport lifecycle ([BoardTransport.transportState]) and the rolling scan list
 * ([BoardTransport.scanResults]) into [ConnectionMsg]s, interprets the [ConnectionEffect]s against the
 * [BoardTransport] driver and the [RememberedBoardStore], and exposes [state] as a [StateFlow].
 *
 * The screen drives the OS permission handshake (the [BlePermissionController]) and feeds the boolean
 * result back via [onPermissionGranted] / [onPermissionDenied]; everything else flows from the two
 * collected streams plus the user's tap / forget / retry intents.
 *
 * Bound only in the Android/iOS Koin modules (like [org.rurbaniak.smartchessboard.presentation.physical.PhysicalPlayViewModel])
 * because [BoardTransport] exists only there — web is digital-only and never reaches this screen.
 */
class ConnectionViewModel(
    private val transport: BoardTransport,
    private val rememberedBoards: RememberedBoardStore,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            ConnectionUiState(
                phase = ConnectionPhase.NeedsPermission,
                // Seeded once at construction so the pure reducer can decide auto-connect without IO.
                rememberedBoardId = rememberedBoards.rememberedId(),
            ),
        )
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    init {
        // Subscribe to both inbound streams before the screen drives anything, so neither a transport
        // state change nor a discovered board is missed.
        viewModelScope.launch {
            transport.transportState.collect { dispatch(ConnectionMsg.TransportStateChanged(it)) }
        }
        viewModelScope.launch {
            transport.scanResults.collect { dispatch(ConnectionMsg.ScanResultsChanged(it)) }
        }
    }

    /** The screen calls this once the OS BLE permission is granted (or was already held). */
    fun onPermissionGranted() = dispatch(ConnectionMsg.PermissionGranted)

    /** The screen calls this when the user denied the OS BLE permission. */
    fun onPermissionDenied() = dispatch(ConnectionMsg.PermissionDenied)

    /** The user tapped a board in the scan list. */
    fun selectDevice(id: String) = dispatch(ConnectionMsg.DeviceSelected(id))

    /** The user chose "Forget device". */
    fun forgetDevice() = dispatch(ConnectionMsg.ForgetDevice)

    /** The user tapped Retry after a failure. */
    fun retry() = dispatch(ConnectionMsg.Retry)

    /** The single funnel: reduce, publish the next state, then run whatever effects it requested. */
    private fun dispatch(msg: ConnectionMsg) {
        val (next, effects) = reduceConnection(_state.value, msg)
        _state.value = next
        effects.forEach(::runEffect)
    }

    private fun runEffect(effect: ConnectionEffect) {
        when (effect) {
            ConnectionEffect.StartScan -> transport.startScan()
            ConnectionEffect.StopScan -> transport.stopScan()
            is ConnectionEffect.Connect -> connect(effect.id)
            ConnectionEffect.Disconnect -> disconnect()
            is ConnectionEffect.RememberBoard -> rememberedBoards.remember(effect.id)
            ConnectionEffect.ForgetBoard -> rememberedBoards.forget()
        }
    }

    private fun connect(id: String) {
        viewModelScope.launch {
            try {
                transport.connect(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A connect that throws outright (rather than driving the transport to a failure state)
                // would otherwise leave the screen stuck "Connecting…"; surface it as a dropped link so
                // the reducer lands on Failed.
                dispatch(ConnectionMsg.TransportStateChanged(BoardTransportState.Disconnected))
            }
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            try {
                transport.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort drop (the Forget path); the radio is released on graph teardown regardless.
            }
        }
    }

    /**
     * The connection persists into the physical-play screen (the same singleton adapter), so teardown
     * deliberately does NOT disconnect — it only stops an in-flight scan to release the radio if the
     * user leaves before a board is chosen. A live link is dropped on graph teardown (Koin `onClose`).
     */
    override fun onCleared() {
        transport.stopScan()
        super.onCleared()
    }
}
