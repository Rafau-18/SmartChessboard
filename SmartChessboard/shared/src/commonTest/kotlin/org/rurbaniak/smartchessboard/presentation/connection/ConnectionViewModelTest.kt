package org.rurbaniak.smartchessboard.presentation.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.domain.board.BoardTransportState
import org.rurbaniak.smartchessboard.domain.board.DiscoveredBoard
import org.rurbaniak.smartchessboard.domain.board.RememberedBoardStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Integration of the pure reducer with the impure shell: drives a fake BoardTransport + RememberedBoardStore
// through the ViewModel and asserts the effects reach the driver (scan/connect/disconnect) and the store
// (remember/forget). The branch coverage lives in ConnectionReducerTest; this proves the wiring.
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBoardTransport : BoardTransport {
        val transportStateFlow = MutableStateFlow(BoardTransportState.Idle)
        val scanResultsFlow = MutableStateFlow<List<DiscoveredBoard>>(emptyList())
        override val transportState: StateFlow<BoardTransportState> = transportStateFlow
        override val scanResults: Flow<List<DiscoveredBoard>> = scanResultsFlow

        var startScanCount = 0
        var stopScanCount = 0
        val connectCalls = mutableListOf<String>()
        var disconnectCount = 0
        var reconnectCount = 0

        override fun startScan() {
            startScanCount++
        }

        override fun stopScan() {
            stopScanCount++
        }

        override suspend fun connect(id: String) {
            connectCalls += id
        }

        override suspend fun reconnect() {
            reconnectCount++
        }

        override suspend fun disconnect() {
            disconnectCount++
        }
    }

    private class FakeRememberedBoardStore(
        private var id: String? = null,
    ) : RememberedBoardStore {
        val rememberCalls = mutableListOf<String>()
        var forgetCount = 0

        override fun rememberedId(): String? = id

        override fun remember(id: String) {
            this.id = id
            rememberCalls += id
        }

        override fun forget() {
            id = null
            forgetCount++
        }
    }

    @Test
    fun permissionGrantedWithoutRememberedStartsScan() =
        runTest {
            val transport = FakeBoardTransport()
            val vm = ConnectionViewModel(transport, FakeRememberedBoardStore())
            advanceUntilIdle() // let the init collectors attach

            vm.onPermissionGranted()
            advanceUntilIdle()

            assertEquals(1, transport.startScanCount)
            assertEquals(ConnectionPhase.Scanning, vm.state.value.phase)
        }

    @Test
    fun rememberedBoardAutoConnectsThenPersistsOnConnected() =
        runTest {
            val transport = FakeBoardTransport()
            val store = FakeRememberedBoardStore(id = "board-1")
            val vm = ConnectionViewModel(transport, store)
            advanceUntilIdle()

            // Entry: permission granted → scan (auto-connect needs a fresh advertisement first).
            vm.onPermissionGranted()
            advanceUntilIdle()
            assertEquals(1, transport.startScanCount)

            // The remembered board appears in the scan → auto-connect without a tap.
            transport.scanResultsFlow.value = listOf(DiscoveredBoard("board-1", "Board", -40))
            advanceUntilIdle()
            assertEquals(listOf("board-1"), transport.connectCalls)

            // The adapter reports the link up → the board is persisted and the screen is Connected.
            transport.transportStateFlow.value = BoardTransportState.Connected
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Connected, vm.state.value.phase)
            assertEquals(listOf("board-1"), store.rememberCalls)
        }

    @Test
    fun selectDeviceConnectsAndConnectedPersists() =
        runTest {
            val transport = FakeBoardTransport()
            val store = FakeRememberedBoardStore()
            val vm = ConnectionViewModel(transport, store)
            advanceUntilIdle()
            vm.onPermissionGranted()
            advanceUntilIdle()

            vm.selectDevice("board-x")
            advanceUntilIdle()
            assertEquals(listOf("board-x"), transport.connectCalls)
            assertTrue(transport.stopScanCount >= 1)

            transport.transportStateFlow.value = BoardTransportState.Connected
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Connected, vm.state.value.phase)
            assertEquals(listOf("board-x"), store.rememberCalls)
        }

    @Test
    fun forgetDisconnectsForgetsAndRescans() =
        runTest {
            val transport = FakeBoardTransport()
            val store = FakeRememberedBoardStore(id = "board-1")
            val vm = ConnectionViewModel(transport, store)
            advanceUntilIdle()

            vm.forgetDevice()
            advanceUntilIdle()

            assertEquals(1, transport.disconnectCount)
            assertEquals(1, store.forgetCount)
            assertTrue(transport.startScanCount >= 1)
            assertEquals(ConnectionPhase.Scanning, vm.state.value.phase)
            assertEquals(null, vm.state.value.rememberedBoardId)
        }

    @Test
    fun permissionDeniedSurfacesDeniedPhaseAndNoScan() =
        runTest {
            val transport = FakeBoardTransport()
            val vm = ConnectionViewModel(transport, FakeRememberedBoardStore())
            advanceUntilIdle()

            vm.onPermissionDenied()
            advanceUntilIdle()

            assertEquals(ConnectionPhase.PermissionDenied, vm.state.value.phase)
            assertEquals(0, transport.startScanCount)
        }

    @Test
    fun scanThatNeverFindsTheBoardTimesOutToAFailure() =
        runTest {
            // A bounded scan window: if the board never appears (single-central / out of range / off), fail
            // to a Retry-able state instead of spinning forever. Prod passes the window via DI; here 5 s.
            val transport = FakeBoardTransport()
            val vm = ConnectionViewModel(transport, FakeRememberedBoardStore(), scanTimeoutMs = 5_000L)
            advanceUntilIdle()

            vm.onPermissionGranted()
            advanceUntilIdle() // scan starts, then the 5 s window elapses with no board found

            assertIs<ConnectionPhase.Failed>(vm.state.value.phase)
            assertTrue(transport.stopScanCount >= 1)
        }
}
