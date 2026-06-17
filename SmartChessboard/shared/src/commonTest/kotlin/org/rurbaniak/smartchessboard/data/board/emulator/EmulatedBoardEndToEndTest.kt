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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The F-02 acceptance proof and S-06's copy-paste example. It scripts a real game fragment through the
// EmulatedBoard scenario helpers and asserts the EXACT, ORDERED typed-event stream a consumer receives
// from the BoardConnection port. By the Phase 3 emission pipeline, every asserted event has made the
// round trip typed → §1.3 bytes → typed through the shared codec, so "the port delivered this stream"
// means "these §1.3 frames went over the wire" — verification here transfers to the real board (S-09).
//
// Reading guide for an S-06 author: everything below is reachable from the public API — subscribe to
// `events` before driving, call the scenario helpers, press buttons explicitly, assert ordered lists.
// No emulator internals are touched. The boards are built with statusInterval = Duration.INFINITE so the
// periodic ~30 s DEVICE_STATUS never interleaves with the asserted stream.
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatedBoardEndToEndTest {
    /**
     * One continuous session exercising every §1.3 message type and both research-mandated ordering
     * variants. Asserted in ordered checkpoints (the move stream, the diagnostic burst, the reconnect
     * snapshot) so each phase of the session reads as its own paragraph of documentation.
     */
    @Test
    fun playsAFullScriptedSessionThroughThePort() =
        runTest {
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
            val events = recordEvents(board)
            var cursor = 0

            fun since(): List<BoardEvent> = events.drop(cursor).also { cursor = events.size }

            // --- Checkpoint 1: connect from the start position, then a scripted opening ---
            // A Ruy Lopez exchange fragment: quiet moves, both capture orderings, an interleaved castle,
            // and a j'adoube — each move confirmed with the moving side's button (§1.5).
            board.connect()
            board.quietMove(sq('e', 2), sq('e', 4)) // 1. e4
            board.pressButton(BoardButton.WHITE)
            board.quietMove(sq('d', 7), sq('d', 5)) // 1... d5
            board.pressButton(BoardButton.BLACK)
            board.capture(from = sq('e', 4), target = sq('d', 5), order = CaptureOrder.MOVER_FIRST) // 2. exd5
            board.pressButton(BoardButton.WHITE)
            board.capture(from = sq('d', 8), target = sq('d', 5), order = CaptureOrder.CAPTURED_FIRST) // 2... Qxd5
            board.pressButton(BoardButton.BLACK)
            board.quietMove(sq('g', 1), sq('f', 3)) // 3. Nf3
            board.pressButton(BoardButton.WHITE)
            board.quietMove(sq('g', 8), sq('f', 6)) // 3... Nf6
            board.pressButton(BoardButton.BLACK)
            board.quietMove(sq('f', 1), sq('c', 4)) // 4. Bc4
            board.pressButton(BoardButton.WHITE)
            board.quietMove(sq('f', 8), sq('c', 5)) // 4... Bc5
            board.pressButton(BoardButton.BLACK)
            board.castle(
                kingFrom = sq('e', 1),
                kingTo = sq('g', 1),
                rookFrom = sq('h', 1),
                rookTo = sq('f', 1),
                order = CastleOrder.INTERLEAVED,
            ) // 5. O-O
            board.pressButton(BoardButton.WHITE)
            board.adjust(sq('c', 5)) // Black straightens the bishop — j'adoube, no button

            assertEquals(
                listOf(
                    startSnapshot(),
                    DEFAULT_STATUS,
                    lift(sq('e', 2)),
                    place(sq('e', 4)),
                    WHITE_BUTTON,
                    lift(sq('d', 7)),
                    place(sq('d', 5)),
                    BLACK_BUTTON,
                    // exd5, MOVER_FIRST: capturing pawn lifted first, then the captured pawn, then it lands.
                    lift(sq('e', 4)),
                    lift(sq('d', 5)),
                    place(sq('d', 5)),
                    WHITE_BUTTON,
                    // Qxd5, CAPTURED_FIRST: the pawn on d5 is removed first, then the queen moves onto it.
                    lift(sq('d', 5)),
                    lift(sq('d', 8)),
                    place(sq('d', 5)),
                    BLACK_BUTTON,
                    lift(sq('g', 1)),
                    place(sq('f', 3)),
                    WHITE_BUTTON,
                    lift(sq('g', 8)),
                    place(sq('f', 6)),
                    BLACK_BUTTON,
                    lift(sq('f', 1)),
                    place(sq('c', 4)),
                    WHITE_BUTTON,
                    lift(sq('f', 8)),
                    place(sq('c', 5)),
                    BLACK_BUTTON,
                    // O-O, INTERLEAVED: king and rook both lifted before either is placed.
                    lift(sq('e', 1)),
                    lift(sq('h', 1)),
                    place(sq('g', 1)),
                    place(sq('f', 1)),
                    WHITE_BUTTON,
                    // adjust: lift and replace the same square — a no-op move pair, no button follows.
                    lift(sq('c', 5)),
                    place(sq('c', 5)),
                ),
                since(),
                "opening move stream",
            )

            // --- Checkpoint 2: diagnostic mode streams ~10 Hz snapshots, GAME mode stops it (§1.6) ---
            val occupancyAfterOpening = board.occupancy
            board.send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))
            // 1050 ms lands strictly between the 10th (1000 ms) and 11th (1100 ms) ticks, so the count is
            // an unambiguous 10 regardless of advanceTimeBy's inclusive/exclusive boundary across versions.
            advanceTimeBy(1050.milliseconds)
            runCurrent()
            assertEquals(
                List(10) { BoardEvent.BoardSnapshot(occupancyAfterOpening) },
                since(),
                "diagnostic mode streams exactly ten identical snapshots over ~1 s at 10 Hz",
            )

            board.send(BoardCommand.SetMode(BoardMode.GAME))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(emptyList(), since(), "returning to GAME mode stops the diagnostic stream")

            // --- Checkpoint 3: a move made while disconnected surfaces only in the reconnect snapshot (S-08) ---
            board.disconnect()
            board.quietMove(sq('a', 2), sq('a', 3)) // played on the physical board while the link is down
            assertEquals(emptyList(), since(), "offline moves emit nothing — a dead link delivers nothing")

            val occupancyWhileOffline = board.occupancy
            board.connect()
            assertEquals(
                listOf(BoardEvent.BoardSnapshot(occupancyWhileOffline), DEFAULT_STATUS),
                since(),
                "the reconnect snapshot reveals the offline move — the reconcile-on-reconnect shape S-08 relies on",
            )
        }

    /**
     * Minimal variant: a pawn promotes and the player confirms the move BEFORE choosing the promotion
     * piece. The board carries the push (lift + place) and the button regardless — the §1.5 rule that the
     * confirmation blocks until a promotion choice is made lives in S-06, not in the event source.
     */
    @Test
    fun promotionPushFollowedByConfirmCarriesBothEvents() =
        runTest {
            // A lone white pawn on a7; a8 is empty so the push is occupancy-consistent.
            val board =
                EmulatedBoard(
                    scope = backgroundScope,
                    initialOccupancy = 1L shl sq('a', 7),
                    statusInterval = Duration.INFINITE,
                )
            val events = recordEvents(board)

            board.connect()
            board.promotionPush(sq('a', 7), sq('a', 8))
            board.pressButton(BoardButton.WHITE)

            assertEquals(
                listOf(
                    BoardEvent.BoardSnapshot(1L shl sq('a', 7)),
                    DEFAULT_STATUS,
                    lift(sq('a', 7)),
                    place(sq('a', 8)),
                    WHITE_BUTTON,
                ),
                events,
            )
        }

    /**
     * Minimal variant: en passant — the captured pawn sits on a different square from where the
     * capturing pawn lands, the trait that distinguishes it from an ordinary capture in the stream.
     */
    @Test
    fun enPassantRemovesCapturedPawnAndLandsBehindIt() =
        runTest {
            // White pawn on d5, black pawn on c5 (just advanced two); white captures onto the empty c6.
            val board =
                EmulatedBoard(
                    scope = backgroundScope,
                    initialOccupancy = (1L shl sq('d', 5)) or (1L shl sq('c', 5)),
                    statusInterval = Duration.INFINITE,
                )
            val events = recordEvents(board)

            board.connect()
            board.enPassant(
                from = sq('d', 5),
                to = sq('c', 6),
                capturedSquare = sq('c', 5),
                order = CaptureOrder.MOVER_FIRST,
            )

            assertEquals(
                listOf(
                    BoardEvent.BoardSnapshot((1L shl sq('d', 5)) or (1L shl sq('c', 5))),
                    DEFAULT_STATUS,
                    // MOVER_FIRST: the capturing pawn (d5) leaves first, then the captured pawn (c5), then d-pawn lands on c6.
                    lift(sq('d', 5)),
                    lift(sq('c', 5)),
                    place(sq('c', 6)),
                ),
                events,
            )
        }

    /**
     * Subscribes an eager, unconfined collector on backgroundScope before returning, so events emitted by
     * the immediately-following driver calls are recorded — the port is hot/no-replay, so a late subscriber
     * would miss the connect burst. The returned list grows as the board emits.
     */
    private fun TestScope.recordEvents(board: EmulatedBoard): MutableList<BoardEvent> {
        val received = mutableListOf<BoardEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            board.events.collect { received.add(it) }
        }
        return received
    }

    private companion object {
        /** Square index from algebraic coordinates — delegates to the single Square.kt authority (a1 = 0). */
        fun sq(
            file: Char,
            rank: Int,
        ): Int = squareOf(file = file - 'a', rank = rank - 1)

        fun lift(square: Int) = BoardEvent.SquareEvent(square, SquareEventType.LIFT)

        fun place(square: Int) = BoardEvent.SquareEvent(square, SquareEventType.PLACE)

        val WHITE_BUTTON = BoardEvent.ButtonEvent(BoardButton.WHITE)
        val BLACK_BUTTON = BoardEvent.ButtonEvent(BoardButton.BLACK)
        val DEFAULT_STATUS = BoardEvent.DeviceStatus(100, FirmwareVersion(1, 0, 0), 0L)

        fun startSnapshot() = BoardEvent.BoardSnapshot(EmulatedBoard.STARTING_POSITION_OCCUPANCY)
    }
}
