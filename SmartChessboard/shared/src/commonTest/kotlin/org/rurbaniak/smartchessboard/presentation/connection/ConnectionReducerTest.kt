package org.rurbaniak.smartchessboard.presentation.connection

import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Pure (state, msg) transitions of the connection reducer. No coroutines, no IO — reduceConnection is a
// pure function, so each case is a direct call + assertion. Covers the full machine: permission gate,
// scan → select → connect → pair → connected, remembered-device auto-connect, forget/re-pair, retry,
// and the BoardTransportState → failure-taxonomy mapping (success criteria 5.1).
class ConnectionReducerTest {
    private fun state(
        phase: ConnectionPhase = ConnectionPhase.NeedsPermission,
        devices: List<DiscoveredBoard> = emptyList(),
        rememberedBoardId: String? = null,
        connectingTo: String? = null,
    ) = ConnectionUiState(phase, devices, rememberedBoardId, connectingTo)

    private fun board(
        id: String,
        name: String? = id,
        rssi: Int? = -50,
    ) = DiscoveredBoard(id, name, rssi)

    // --- Permission gate ---

    @Test
    fun permissionGrantedWithoutRememberedStartsScanning() {
        val result = reduceConnection(state(), ConnectionMsg.PermissionGranted)
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertEquals(listOf(ConnectionEffect.StartScan), result.effects)
    }

    @Test
    fun permissionGrantedWithRememberedStillScansFirst() {
        // Auto-connect cannot connect blind — the adapter needs a fresh advertisement, so it scans first
        // and onScanResults auto-selects the remembered board when it reappears.
        val result = reduceConnection(state(rememberedBoardId = "board-1"), ConnectionMsg.PermissionGranted)
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertEquals(listOf(ConnectionEffect.StartScan), result.effects)
        assertEquals("board-1", result.state.rememberedBoardId)
    }

    @Test
    fun permissionGrantedWhileAlreadyConnectedDoesNotReScan() {
        // The singleton adapter keeps the link across screens, so re-entering this gate to resume a game
        // finds it already Connected (transportState replays Connected to the fresh VM). Granting
        // permission must NOT start a scan — a connected board isn't advertising, so a scan would hang on
        // "Looking for your saved board…" forever. (S-09 Phase 8 — the now-stable plaintext link exposed this.)
        val result =
            reduceConnection(
                state(ConnectionPhase.Connected, rememberedBoardId = "board-1"),
                ConnectionMsg.PermissionGranted,
            )
        assertEquals(ConnectionPhase.Connected, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun permissionGrantedWhileConnectingDoesNotReScan() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false)),
                ConnectionMsg.PermissionGranted,
            )
        assertIs<ConnectionPhase.Connecting>(result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun permissionDeniedShowsDeniedPhase() {
        val result = reduceConnection(state(), ConnectionMsg.PermissionDenied)
        assertEquals(ConnectionPhase.PermissionDenied, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    // --- Scan + select + connect ---

    @Test
    fun scanResultsRefreshTheDeviceList() {
        val devices = listOf(board("a"), board("b"))
        val result = reduceConnection(state(ConnectionPhase.Scanning), ConnectionMsg.ScanResultsChanged(devices))
        assertEquals(devices, result.state.devices)
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun scanTimedOutWhileScanningFailsOutWithStopScan() {
        // The board never appeared in the scan window — fail to a Retry-able state, not an endless spinner.
        val result =
            reduceConnection(state(ConnectionPhase.Scanning, rememberedBoardId = "board-1"), ConnectionMsg.ScanTimedOut)
        val phase = assertIs<ConnectionPhase.Failed>(result.state.phase)
        assertEquals(ConnectionFailure.OUT_OF_RANGE, phase.reason)
        assertEquals(listOf(ConnectionEffect.StopScan), result.effects)
    }

    @Test
    fun scanTimedOutAfterWeAlreadyMovedOnIsIgnored() {
        // A late timeout (a connect already started) must not clobber the Connecting phase.
        val result =
            reduceConnection(state(ConnectionPhase.Connecting(pairing = false)), ConnectionMsg.ScanTimedOut)
        assertIs<ConnectionPhase.Connecting>(result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun rememberedBoardReappearingAutoConnects() {
        val devices = listOf(board("other"), board("board-1"))
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning, rememberedBoardId = "board-1"),
                ConnectionMsg.ScanResultsChanged(devices),
            )
        assertEquals(ConnectionPhase.Connecting(pairing = false), result.state.phase)
        assertEquals("board-1", result.state.connectingTo)
        assertEquals(listOf(ConnectionEffect.StopScan, ConnectionEffect.Connect("board-1")), result.effects)
    }

    @Test
    fun rememberedBoardAbsentJustUpdatesList() {
        val devices = listOf(board("other"))
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning, rememberedBoardId = "board-1"),
                ConnectionMsg.ScanResultsChanged(devices),
            )
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertEquals(devices, result.state.devices)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun deviceSelectedStopsScanAndConnects() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning, devices = listOf(board("a"))),
                ConnectionMsg.DeviceSelected("a"),
            )
        assertEquals(ConnectionPhase.Connecting(pairing = false), result.state.phase)
        assertEquals("a", result.state.connectingTo)
        assertEquals(listOf(ConnectionEffect.StopScan, ConnectionEffect.Connect("a")), result.effects)
    }

    @Test
    fun connectedRemembersTheBoard() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Connected),
            )
        assertEquals(ConnectionPhase.Connected, result.state.phase)
        assertEquals("a", result.state.rememberedBoardId)
        assertEquals(listOf(ConnectionEffect.RememberBoard("a")), result.effects)
    }

    @Test
    fun duplicateConnectedIsIdempotent() {
        // A second Connected (e.g. observe re-emits) stays Connected and re-remembers the same id.
        val result =
            reduceConnection(
                state(ConnectionPhase.Connected, rememberedBoardId = "a", connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Connected),
            )
        assertEquals(ConnectionPhase.Connected, result.state.phase)
        assertEquals(listOf(ConnectionEffect.RememberBoard("a")), result.effects)
    }

    @Test
    fun pairingSubStateLightsWhileConnecting() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Pairing),
            )
        assertEquals(ConnectionPhase.Connecting(pairing = true), result.state.phase)
    }

    // --- Failure taxonomy ---

    @Test
    fun bluetoothOffMapsToFailure() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning),
                ConnectionMsg.TransportStateChanged(BoardTransportState.BluetoothOff),
            )
        assertEquals(ConnectionPhase.Failed(ConnectionFailure.BLUETOOTH_OFF), result.state.phase)
    }

    @Test
    fun transportPermissionDeniedMapsToDeniedPhase() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning),
                ConnectionMsg.TransportStateChanged(BoardTransportState.PermissionDenied),
            )
        assertEquals(ConnectionPhase.PermissionDenied, result.state.phase)
    }

    @Test
    fun outOfRangeWhileConnectingFails() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.OutOfRange),
            )
        assertEquals(ConnectionPhase.Failed(ConnectionFailure.OUT_OF_RANGE), result.state.phase)
    }

    @Test
    fun bondFailedWhileConnectingFails() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.BondFailed),
            )
        assertEquals(ConnectionPhase.Failed(ConnectionFailure.BOND_FAILED), result.state.phase)
    }

    @Test
    fun disconnectWhileConnectingIsGenericFailure() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Disconnected),
            )
        assertEquals(ConnectionPhase.Failed(ConnectionFailure.GENERIC), result.state.phase)
    }

    @Test
    fun disconnectWhileConnectedIsOutOfRange() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connected, connectingTo = "a"),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Disconnected),
            )
        assertEquals(ConnectionPhase.Failed(ConnectionFailure.OUT_OF_RANGE), result.state.phase)
    }

    @Test
    fun disconnectWhileScanningIsIgnored() {
        // The echo of StopScan/Disconnect must not knock a scan into a failure.
        val result =
            reduceConnection(
                state(ConnectionPhase.Scanning),
                ConnectionMsg.TransportStateChanged(BoardTransportState.Disconnected),
            )
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    // --- Forget + retry ---

    @Test
    fun forgetDropsLinkClearsRememberedAndRescans() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Connected, rememberedBoardId = "a", connectingTo = "a"),
                ConnectionMsg.ForgetDevice,
            )
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertEquals(null, result.state.rememberedBoardId)
        assertEquals(null, result.state.connectingTo)
        assertEquals(
            listOf(ConnectionEffect.Disconnect, ConnectionEffect.ForgetBoard, ConnectionEffect.StartScan),
            result.effects,
        )
    }

    @Test
    fun retryReconnectsTheTargetedBoard() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Failed(ConnectionFailure.BOND_FAILED), connectingTo = "a"),
                ConnectionMsg.Retry,
            )
        assertEquals(ConnectionPhase.Connecting(pairing = false), result.state.phase)
        assertEquals(listOf(ConnectionEffect.Connect("a")), result.effects)
    }

    @Test
    fun retryWithoutTargetRescans() {
        val result =
            reduceConnection(
                state(ConnectionPhase.Failed(ConnectionFailure.BLUETOOTH_OFF), connectingTo = null),
                ConnectionMsg.Retry,
            )
        assertEquals(ConnectionPhase.Scanning, result.state.phase)
        assertEquals(listOf(ConnectionEffect.StartScan), result.effects)
    }

    // --- Derived helper ---

    @Test
    fun connectedBoardIdIsExposedOnlyWhenConnected() {
        assertEquals("a", state(ConnectionPhase.Connected, connectingTo = "a").connectedBoardId)
        assertEquals(null, state(ConnectionPhase.Connecting(pairing = false), connectingTo = "a").connectedBoardId)
        val failed = reduceConnection(state(ConnectionPhase.Scanning), ConnectionMsg.PermissionDenied)
        assertIs<ConnectionPhase.PermissionDenied>(failed.state.phase)
    }
}
