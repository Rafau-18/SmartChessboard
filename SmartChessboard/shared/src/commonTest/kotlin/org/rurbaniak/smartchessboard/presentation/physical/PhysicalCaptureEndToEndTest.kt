package org.rurbaniak.smartchessboard.presentation.physical

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.data.board.emulator.CaptureOrder
import org.rurbaniak.smartchessboard.data.board.emulator.CastleOrder
import org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard
import org.rurbaniak.smartchessboard.data.board.emulator.capture
import org.rurbaniak.smartchessboard.data.board.emulator.castle
import org.rurbaniak.smartchessboard.data.board.emulator.enPassant
import org.rurbaniak.smartchessboard.data.board.emulator.quietMove
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.rurbaniak.smartchessboard.domain.games.GameStatus as RecordStatus

// The S-06 acceptance proof: scripted physical games driven through the real PhysicalPlayViewModel +
// the reused S-04 journal/auto-saver, asserting every move shape lands in the canonical PGN and the
// finished record round-trips through parsePgn (what S-02 Replay reads). The board is driven exactly
// as the BoardScenarios DSL and the F-02 EmulatedBoardEndToEndTest do — lift/place primitives, both
// capture orderings, an interleaved castle, en passant, a promotion — so the hardest bet (a magnet-only
// occupancy stream resolves to one move) is proven end-to-end, not just at the interpreter unit level.
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalCaptureEndToEndTest {
    private val gameId = "game-e2e"

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeGamesRepository

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeGamesRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sq(
        file: Char,
        rank: Int,
    ): Int = squareOf(file - 'a', rank - 1)

    private class Harness(
        val viewModel: PhysicalPlayViewModel,
        val board: EmulatedBoard,
        val journal: FakeGameJournal,
    )

    /** A fresh PHYSICAL game (empty PGN ⇒ start position), connected with the ViewModel subscribed first. */
    private suspend fun TestScope.connectedGame(): Harness {
        repository.records =
            mapOf(
                gameId to
                    GameRecord(
                        id = gameId,
                        createdAt = "2026-06-19T10:00:00+00:00",
                        mode = GameMode.PHYSICAL,
                        status = RecordStatus.IN_PROGRESS,
                        result = null,
                        whiteLabel = "White",
                        blackLabel = "Black",
                        pgn = "",
                    ),
            )
        val journal = FakeGameJournal()
        val saver = GameAutoSaver(gamesRepository = repository, journal = journal)
        val board = EmulatedBoard(scope = backgroundScope, statusInterval = Duration.INFINITE)
        val viewModel = PhysicalPlayViewModel(gameId, repository, saver, board, parseDispatcher = dispatcher)
        advanceUntilIdle()
        board.connect()
        advanceUntilIdle()
        return Harness(viewModel, board, journal)
    }

    private fun playing(viewModel: PhysicalPlayViewModel): PhysicalPlayState.Playing {
        val state = viewModel.state.value
        assertTrue(state is PhysicalPlayState.Playing, "expected Playing, got $state")
        return state
    }

    // The canonical PGN the cloud holds: a finished game's closing write (the journal entry is cleared
    // once a finished flush is confirmed), else the latest in-progress sync.
    private fun canonicalPgn(): String =
        repository.finishGameCalls.lastOrNull()?.third ?: repository.updatePgnCalls.last().second

    @Test
    fun scriptedGameWithCapturesAndAnInterleavedCastleLandsInTheCanonicalPgn() =
        runTest {
            val h = connectedGame()

            // A wrong-side button before the move is a no-op (chess-clock semantics): nothing is saved.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            assertTrue(playing(h.viewModel).sanMoves.isEmpty(), "wrong-side confirm saves nothing")
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            assertEquals(listOf("e4"), playing(h.viewModel).sanMoves)

            h.board.quietMove(sq('e', 7), sq('e', 5))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            h.board.quietMove(sq('g', 1), sq('f', 3))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            h.board.quietMove(sq('b', 8), sq('c', 6))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            h.board.quietMove(sq('f', 1), sq('b', 5))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            h.board.quietMove(sq('a', 7), sq('a', 6))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()

            // 4. Bxc6 — mover lifted first; 4... dxc6 — captured piece lifted first. Both resolve identically.
            h.board.capture(from = sq('b', 5), target = sq('c', 6), order = CaptureOrder.MOVER_FIRST)
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            h.board.capture(from = sq('d', 7), target = sq('c', 6), order = CaptureOrder.CAPTURED_FIRST)
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()

            // 5. O-O — king and rook both airborne before either lands (the messiest ordering).
            h.board.castle(
                kingFrom = sq('e', 1),
                kingTo = sq('g', 1),
                rookFrom = sq('h', 1),
                rookTo = sq('f', 1),
                order = CastleOrder.INTERLEAVED,
            )
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            val expected = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6", "dxc6", "O-O")
            assertEquals(expected, playing(h.viewModel).sanMoves)

            // Manual end records a draw (FR-018); the finished record round-trips through parsePgn.
            h.viewModel.requestEndGame()
            h.viewModel.pickResult(GameResult.DRAW)
            h.viewModel.confirmEndGame()
            advanceUntilIdle()

            assertEquals(GameResult.DRAW, playing(h.viewModel).result)
            val stored = canonicalPgn()
            assertTrue(stored.contains("[Mode \"physical\"]"), "stored PGN must tag the physical mode")
            assertTrue(stored.contains("1/2-1/2"), "manual end records the result token")
            val replay = parsePgn(stored)
            assertEquals(expected, replay.sanMoves, "stored PGN round-trips to the same moves")
            assertEquals(playing(h.viewModel).positions, replay.positions, "and to the same positions S-02 replays")
        }

    @Test
    fun enPassantResolvesFromTheStreamAndRoundTrips() =
        runTest {
            val h = connectedGame()
            // 1. e4 Nf6 2. e5 d5 3. exd6 e.p. — the captured pawn sits on d5, not the d6 landing square.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            h.board.quietMove(sq('g', 8), sq('f', 6))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            h.board.quietMove(sq('e', 4), sq('e', 5))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            h.board.quietMove(sq('d', 7), sq('d', 5))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            h.board.enPassant(
                from = sq('e', 5),
                to = sq('d', 6),
                capturedSquare = sq('d', 5),
                order = CaptureOrder.MOVER_FIRST,
            )
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            val expected = listOf("e4", "Nf6", "e5", "d5", "exd6")
            assertEquals(expected, playing(h.viewModel).sanMoves)
            val stored = canonicalPgn()
            assertTrue(stored.contains("[Mode \"physical\"]"))
            assertEquals(expected, parsePgn(stored).sanMoves)
        }

    @Test
    fun promotionPushRaisesThePickerAndAConfirmBeforePickingSavesNothing() =
        runTest {
            val h = connectedGame()
            // 1. e4 d5 2. exd5 c6 3. dxc6 Nf6 4. cxb7 e6 — the e-pawn marches to b7 via three captures.
            drive(h, sq('e', 2), sq('e', 4), BoardButton.WHITE)
            drive(h, sq('d', 7), sq('d', 5), BoardButton.BLACK)
            driveCapture(h, sq('e', 4), sq('d', 5), CaptureOrder.MOVER_FIRST, BoardButton.WHITE)
            drive(h, sq('c', 7), sq('c', 6), BoardButton.BLACK)
            driveCapture(h, sq('d', 5), sq('c', 6), CaptureOrder.CAPTURED_FIRST, BoardButton.WHITE)
            drive(h, sq('g', 8), sq('f', 6), BoardButton.BLACK)
            driveCapture(h, sq('c', 6), sq('b', 7), CaptureOrder.MOVER_FIRST, BoardButton.WHITE)
            drive(h, sq('e', 7), sq('e', 6), BoardButton.BLACK)

            // 5. bxa8=Q — the place on the last rank raises the picker (contract §1.5).
            h.board.capture(from = sq('b', 7), target = sq('a', 8), order = CaptureOrder.CAPTURED_FIRST)
            advanceUntilIdle()
            assertNotNull(playing(h.viewModel).pendingPromotion, "landing a pawn on the last rank raises the picker")
            val sansBeforePick = playing(h.viewModel).sanMoves.size

            // A confirm pressed before the piece is chosen is a reminder, not a save.
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            assertEquals(RejectionReason.PROMOTION_REQUIRED, playing(h.viewModel).rejection)
            assertEquals(sansBeforePick, playing(h.viewModel).sanMoves.size, "confirm before picking saves nothing")

            // Picking the piece commits the promotion capture.
            h.viewModel.pickPromotion(PieceType.QUEEN)
            advanceUntilIdle()

            val sans = playing(h.viewModel).sanMoves
            assertEquals("bxa8=Q", sans.last())
            val stored = canonicalPgn()
            assertTrue(stored.contains("[Mode \"physical\"]"))
            assertEquals(sans, parsePgn(stored).sanMoves, "the promotion round-trips")
        }

    @Test
    fun aMatingMoveAutoClosesTheGame() =
        runTest {
            val h = connectedGame()
            // Fool's mate: 1. f3 e5 2. g4 Qh4# — the confirming move ends the game (FR-007 auto-close).
            drive(h, sq('f', 2), sq('f', 3), BoardButton.WHITE)
            drive(h, sq('e', 7), sq('e', 5), BoardButton.BLACK)
            drive(h, sq('g', 2), sq('g', 4), BoardButton.WHITE)
            drive(h, sq('d', 8), sq('h', 4), BoardButton.BLACK)

            val state = playing(h.viewModel)
            assertTrue(state.terminal, "the position is checkmate")
            assertEquals(GameResult.BLACK, state.result, "Black delivered mate, so Black is recorded the winner")
            val stored = canonicalPgn()
            assertTrue(stored.contains("[Mode \"physical\"]"))
            assertTrue(stored.contains("0-1"), "the auto-close records the result token")
            assertEquals(listOf("f3", "e5", "g4", "Qh4#"), parsePgn(stored).sanMoves)
        }

    // --- driving helpers ---

    private suspend fun TestScope.drive(
        harness: Harness,
        from: Int,
        to: Int,
        button: BoardButton,
    ) {
        harness.board.quietMove(from, to)
        harness.board.pressButton(button)
        advanceUntilIdle()
    }

    private suspend fun TestScope.driveCapture(
        harness: Harness,
        from: Int,
        target: Int,
        order: CaptureOrder,
        button: BoardButton,
    ) {
        harness.board.capture(from = from, target = target, order = order)
        harness.board.pressButton(button)
        advanceUntilIdle()
    }
}
