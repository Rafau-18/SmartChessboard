package org.rurbaniak.smartchessboard.presentation.physical

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard
import org.rurbaniak.smartchessboard.data.board.emulator.quietMove
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.toOccupancy
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.rurbaniak.smartchessboard.domain.games.GameStatus as RecordStatus

/**
 * S-08 end-to-end resume gate (FR-013) against the real [PhysicalPlayViewModel] + [EmulatedBoard]:
 * simulate an app restart mid physical game (a fresh ViewModel over a record whose PGN already holds
 * accepted moves) and prove the board-confirmation gate — auto-resume when the board matches the
 * rebuilt position, the diagnostics→restore loop when it does not, and the §6.2 invariant that **no
 * accepted move is lost across the restart**. Sibling of [PhysicalRecoverEndToEndTest]; same harness
 * (real auto-saver over a [FakeGameJournal], emulator on `backgroundScope`, `StandardTestDispatcher`
 * shared with `parseDispatcher`).
 *
 * Fault injection: a fresh [EmulatedBoard] reports start-position occupancy on connect, so a mid-game
 * resume against the emulator default *always* mismatches. Each scenario therefore constructs the
 * board's `initialOccupancy` explicitly — the expected occupancy of `positions.last()` for the match
 * case, that occupancy minus one piece for the mismatch / promotion-pending cases — exactly as the
 * recover suite injects the occupancy it wants rather than relying on the emulator default.
 *
 * Timing: the resumed harness settles `load()` with [advanceUntilIdle] **while the board is still
 * disconnected** (no diagnostic stream to spin), then settles the connect burst with [runCurrent] so a
 * mismatch's armed ~10 Hz diagnostic stream does not make a later [advanceUntilIdle] hang. A restore is
 * delivered with [tick] (advance exactly one snapshot); the gate-clearing match stops the stream, so
 * the post-resume move can settle with [advanceUntilIdle] again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalResumeEndToEndTest {
    private val gameId = "game-resume"
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

    @Test
    fun `a matching board auto-resumes with no extra input and no accepted move lost`() =
        runTest {
            // Two accepted moves were journaled before the (simulated) restart; the board powers on exactly
            // matching the rebuilt position.
            val pgn = "1. e4 e5"
            val h = resumedGame(pgn = pgn, boardOccupancy = occupancyAfter(pgn))

            // The PGN rebuilt on resume — both prior moves are present, none lost.
            assertEquals(listOf("e4", "e5"), playing(h.viewModel).sanMoves)

            // Auto-resume-on-match: the gate cleared with no extra input, diagnostics never opened, and the
            // board never left GAME mode — so NOT a single SetMode was emitted (the clean-match path).
            val resumed = playing(h.viewModel)
            assertFalse(resumed.awaitingResumeConfirm, "a matching board clears the resume gate")
            assertFalse(resumed.acceptanceBlocked, "play is re-enabled with no extra input")
            assertFalse(resumed.diagnosticsVisible, "a clean match never opens diagnostics")
            assertTrue(
                h.recording.sent.none { it is BoardCommand.SetMode },
                "a clean match leaves the board in GAME mode — no SetMode at all",
            )
            assertEquals(0, h.acceptedMoveCount(), "resume itself accepts nothing")

            // The next physical move is accepted normally and journaled — nothing was lost across the restart.
            h.board.quietMove(sq('g', 1), sq('f', 3))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(listOf("e4", "e5", "Nf3"), playing(h.viewModel).sanMoves)
            assertEquals(1, h.acceptedMoveCount(), "exactly the one post-resume move was journaled")
        }

    @Test
    fun `a mismatching board blocks acceptance until the board is restored then resumes`() =
        runTest {
            val pgn = "1. e4 e5"
            val expected = occupancyAfter(pgn)
            // The board comes up with the a1 rook missing — a mid-game mismatch against the rebuilt position.
            val missingRook = expected and (1L shl sq('a', 1)).inv()
            val h = resumedGame(pgn = pgn, boardOccupancy = missingRook)

            // Acceptance is blocked and the reed grid auto-opens on the resume mismatch (FR-013 → FR-010/011).
            val gated = playing(h.viewModel)
            assertTrue(gated.awaitingResumeConfirm, "a mismatching board holds the resume gate")
            assertTrue(gated.acceptanceBlocked, "acceptance stays blocked until the board is confirmed")
            assertTrue(gated.diagnosticsVisible, "the mismatch auto-opens diagnostics")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)),
                "a resume mismatch enters diagnostic mode",
            )
            assertEquals(0, h.acceptedMoveCount(), "a gated resume accepts nothing")

            // Restore the board: place the a1 rook back. The diagnostic stream then delivers the matching
            // snapshot that clears the gate — exactly the S-07 restore loop, reached after a restart.
            h.board.place(sq('a', 1))
            tick()

            val restored = playing(h.viewModel)
            assertFalse(restored.awaitingResumeConfirm, "restoring the board clears the resume gate")
            assertFalse(restored.acceptanceBlocked)
            assertFalse(restored.diagnosticsVisible, "the grid closes once the board matches")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.GAME)),
                "a verified restore exits diagnostic mode (one SetMode(GAME) on the shown→hidden edge)",
            )

            // Play resumes: the next physical move is accepted, and nothing was lost across the restart.
            h.board.quietMove(sq('g', 1), sq('f', 3))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(listOf("e4", "e5", "Nf3"), playing(h.viewModel).sanMoves)
            assertEquals(1, h.acceptedMoveCount(), "exactly the one post-restore move was journaled")
        }

    @Test
    fun `a promotion lifted in-hand at kill is never accepted and resumes via diagnostics`() =
        runTest {
            // The journaled game leaves a White pawn on a7, ready to promote, with White to move. At the
            // force-quit the player had lifted that pawn (in hand, mid-promotion gesture) — so the promotion
            // move was never accepted (never journaled). On resume the board reads a7 empty ⇒ mismatch.
            val pgn = "1. b4 a5 2. bxa5 Nf6 3. a6 e6 4. a7 Be7"
            val expected = occupancyAfter(pgn)
            val pawnInHand = expected and (1L shl sq('a', 7)).inv()
            val h = resumedGame(pgn = pgn, boardOccupancy = pawnInHand)

            // The eight accepted plies survive the restart — the in-flight promotion is NOT among them.
            assertEquals(
                listOf("b4", "a5", "bxa5", "Nf6", "a6", "e6", "a7", "Be7"),
                playing(h.viewModel).sanMoves,
                "the never-accepted promotion is absent — nothing was lost",
            )
            val gated = playing(h.viewModel)
            assertTrue(gated.awaitingResumeConfirm, "the lifted-pawn board mismatches ⇒ the resume gate holds")
            assertTrue(gated.acceptanceBlocked)
            assertTrue(gated.diagnosticsVisible, "the mismatch routes to diagnostics")
            assertEquals(0, h.acceptedMoveCount(), "the in-flight promotion was never accepted")

            // Restore: set the pawn back on a7. The diagnostic stream delivers the matching snapshot.
            h.board.place(sq('a', 7))
            tick()

            val restored = playing(h.viewModel)
            assertFalse(restored.awaitingResumeConfirm, "restoring the pawn clears the resume gate")
            assertFalse(restored.acceptanceBlocked)
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.GAME)),
                "a verified restore exits diagnostic mode",
            )

            // Play resumes from the exact pre-promotion position: a normal move is accepted, none lost.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(
                listOf("b4", "a5", "bxa5", "Nf6", "a6", "e6", "a7", "Be7", "e4"),
                playing(h.viewModel).sanMoves,
            )
            assertEquals(1, h.acceptedMoveCount(), "exactly the one post-restore move was journaled")
        }

    /**
     * Simulate an app restart: a PHYSICAL game whose record already carries [pgn] (accepted moves), a
     * fresh ViewModel, and an emulator powered on with the injected [boardOccupancy]. Settles `load()`
     * while disconnected, then the connect burst with [runCurrent] so an armed diagnostic stream cannot
     * make a later `advanceUntilIdle` hang.
     */
    private suspend fun TestScope.resumedGame(
        pgn: String,
        boardOccupancy: Long,
    ): Harness {
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
                        pgn = pgn,
                    ),
            )
        val journal = FakeGameJournal()
        val saver = GameAutoSaver(gamesRepository = repository, journal = journal)
        val board =
            EmulatedBoard(
                scope = backgroundScope,
                initialOccupancy = boardOccupancy,
                statusInterval = Duration.INFINITE,
            )
        val recording = RecordingBoardConnection(board)
        val viewModel = PhysicalPlayViewModel(gameId, repository, saver, recording, parseDispatcher = dispatcher)
        advanceUntilIdle() // settle load — the board is still disconnected, so no diagnostic stream to spin
        board.connect()
        runCurrent() // settle the connect burst WITHOUT advancing the ~10 Hz diagnostic clock
        return Harness(viewModel, board, recording, journal)
    }

    /** The occupancy the reducer verifies a resume against: the rebuilt `positions.last()` of [pgn]. */
    private fun occupancyAfter(pgn: String): Long = parsePgn(pgn).positions.last().toOccupancy()

    /** Advance one ~10 Hz diagnostic snapshot tick, then settle the work it triggers. */
    private fun TestScope.tick() {
        advanceTimeBy(150.milliseconds)
        runCurrent()
    }

    private fun playing(viewModel: PhysicalPlayViewModel): PhysicalPlayState.Playing {
        val state = viewModel.state.value
        assertTrue(state is PhysicalPlayState.Playing, "expected Playing, got $state")
        return state
    }

    /** Count of *accepted* moves journaled — only `acceptMove`/`finishGame` write `dirty = true`. */
    private fun Harness.acceptedMoveCount(): Int = journal.saveLog.count { (_, _, dirty) -> dirty }

    private fun sq(
        file: Char,
        rank: Int,
    ): Int = squareOf(file - 'a', rank - 1)

    private class Harness(
        val viewModel: PhysicalPlayViewModel,
        val board: EmulatedBoard,
        val recording: RecordingBoardConnection,
        val journal: FakeGameJournal,
    )

    /**
     * Wraps the real [EmulatedBoard] so the test can assert which [BoardCommand]s the ViewModel sent
     * (entering / leaving diagnostic mode) while the board still reacts to them. Transparent for
     * [connectionState] and [events]; records every [send], then delegates.
     */
    private class RecordingBoardConnection(
        private val board: EmulatedBoard,
    ) : BoardConnection {
        val sent = mutableListOf<BoardCommand>()

        override val connectionState: StateFlow<BoardConnectionState> get() = board.connectionState
        override val events: SharedFlow<BoardEvent> get() = board.events

        override suspend fun send(command: BoardCommand) {
            sent += command
            board.send(command)
        }
    }
}
