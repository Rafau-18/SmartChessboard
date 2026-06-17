package org.rurbaniak.smartchessboard.data.board.emulator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.FirmwareVersion
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Behavior matrix for the emulator, all on runTest virtual time. The board's time-driven jobs and the
// event collector run on backgroundScope (auto-cancelled at test end), so the infinite diagnostic /
// status loops never block test completion. Every test that asserts an exact ordered stream constructs
// the board with statusInterval = Duration.INFINITE so the periodic ~30 s status cannot interleave.
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatedBoardTest {
    @Test
    fun connectEmitsSnapshotThenStatus() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)

            board.connect()

            assertEquals(listOf(startSnapshot(), DEFAULT_STATUS), events)
        }

    @Test
    fun liftAndPlaceEmitSquareEventsAndTrackOccupancy() =
        runTest {
            val board =
                EmulatedBoard(scope = backgroundScope, initialOccupancy = 0L, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)

            board.connect()
            board.place(E4)
            assertTrue(isSet(board.occupancy, E4), "place marks the square occupied")
            board.lift(E4)
            assertEquals(0L, board.occupancy, "lift clears it again")

            assertEquals(
                listOf(
                    BoardEvent.BoardSnapshot(0L),
                    DEFAULT_STATUS,
                    BoardEvent.SquareEvent(E4, SquareEventType.PLACE),
                    BoardEvent.SquareEvent(E4, SquareEventType.LIFT),
                ),
                events,
            )
        }

    @Test
    fun liftFromEmptySquareThrows() =
        runTest {
            val board =
                EmulatedBoard(scope = backgroundScope, initialOccupancy = 0L, statusInterval = Duration.INFINITE)
            board.connect()

            assertFailsWith<IllegalStateException> { board.lift(E4) }
        }

    @Test
    fun placeOnOccupiedSquareThrows() =
        runTest {
            // Default start occupancy fills a1 (square 0).
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            board.connect()

            assertFailsWith<IllegalStateException> { board.place(0) }
        }

    @Test
    fun setOccupancyWhileConnectedThrows() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            board.connect()

            assertFailsWith<IllegalStateException> { board.setOccupancy(0L) }
        }

    @Test
    fun offlineMutationSurfacesOnlyInReconnectSnapshot() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)

            board.connect()
            board.disconnect()
            // a2 (square 8) lifted while the link is down: occupancy changes, nothing is emitted.
            board.lift(A2)
            val offlineOccupancy = EmulatedBoard.STARTING_POSITION_OCCUPANCY and (1L shl A2).inv()
            assertEquals(offlineOccupancy, board.occupancy)

            board.connect()

            assertEquals(
                listOf(
                    startSnapshot(),
                    DEFAULT_STATUS,
                    // No SQUARE_EVENT for the offline lift — it surfaces only here, in the reconnect snapshot.
                    BoardEvent.BoardSnapshot(offlineOccupancy),
                    DEFAULT_STATUS,
                ),
                events,
            )
        }

    @Test
    fun buttonPressWhileDisconnectedIsSilentNoOp() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)

            board.pressButton(BoardButton.WHITE)
            assertEquals(emptyList(), events, "a press on a disconnected board is lost, not buffered")

            board.connect()
            board.pressButton(BoardButton.WHITE)

            assertEquals(
                listOf(startSnapshot(), DEFAULT_STATUS, BoardEvent.ButtonEvent(BoardButton.WHITE)),
                events,
            )
        }

    @Test
    fun sendWhileDisconnectedThrows() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)

            assertFailsWith<IllegalStateException> { board.send(BoardCommand.RequestSnapshot) }
        }

    @Test
    fun requestSnapshotAndRequestStatusEmitImmediately() =
        runTest {
            val board =
                EmulatedBoard(scope = backgroundScope, initialOccupancy = 0L, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)
            board.connect()
            val baseline = events.size

            board.send(BoardCommand.RequestSnapshot)
            board.send(BoardCommand.RequestStatus)

            assertEquals(listOf(BoardEvent.BoardSnapshot(0L), DEFAULT_STATUS), events.drop(baseline))
        }

    @Test
    fun diagnosticModeStreamsSnapshotsAtTenHzAndStopsOnGameMode() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)
            board.connect()
            val baseline = events.size // snapshot + status; no periodic status (INFINITE)

            board.send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))
            advanceTimeBy(1.seconds)
            runCurrent()

            val streamed = events.drop(baseline)
            assertTrue(streamed.size in 9..11, "~10 Hz over one second, got ${streamed.size}")
            assertTrue(streamed.all { it is BoardEvent.BoardSnapshot }, "diagnostic stream is snapshots only")

            board.send(BoardCommand.SetMode(BoardMode.GAME))
            val afterStop = events.size
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(afterStop, events.size, "returning to GAME mode stops the snapshot stream")
        }

    @Test
    fun periodicDeviceStatusEmitsAtConfiguredInterval() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = 30.seconds)
            val events = recordEvents(board)

            board.connect()
            assertEquals(1, events.count { it is BoardEvent.DeviceStatus }, "only the on-connect status so far")

            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(2, events.count { it is BoardEvent.DeviceStatus }, "one periodic status after 30 s")

            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(3, events.count { it is BoardEvent.DeviceStatus }, "and another after the next 30 s")
        }

    @Test
    fun reconnectResetsModeToGameSoDiagnosticCanBeReEntered() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)

            board.connect()
            board.send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))
            advanceTimeBy(300.milliseconds)
            runCurrent()
            board.disconnect()

            board.connect() // §1.7: this must reset mode to GAME
            val afterReconnect = events.size

            // If reconnect had NOT reset the mode, the board would still consider itself in DIAGNOSTIC and
            // this command would be a no-op — no stream would start. It must start.
            board.send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertTrue(
                events.size > afterReconnect,
                "diagnostic re-entered after reconnect ⇒ the reconnect reset mode to GAME",
            )
        }

    /**
     * Subscribes an eager, unconfined collector on backgroundScope before returning, so events emitted
     * by the immediately-following driver calls are recorded (the port is hot/no-replay — a late
     * subscriber would miss the connect burst). The returned list grows as the board emits.
     */
    private fun TestScope.recordEvents(board: EmulatedBoard): MutableList<BoardEvent> {
        val received = mutableListOf<BoardEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            board.events.collect { received.add(it) }
        }
        return received
    }

    private companion object {
        // Delegate to the single Square.kt authority instead of re-deriving file + 8*rank here.
        val E4 = squareOf(file = 4, rank = 3) // e4
        val A2 = squareOf(file = 0, rank = 1) // a2

        val DEFAULT_STATUS = BoardEvent.DeviceStatus(100, FirmwareVersion(1, 0, 0), 0L)

        fun startSnapshot() = BoardEvent.BoardSnapshot(EmulatedBoard.STARTING_POSITION_OCCUPANCY)

        fun isSet(
            occupancy: Long,
            square: Int,
        ): Boolean = (occupancy ushr square) and 1L == 1L
    }
}
