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
 * S-09 end-to-end reconnect-reconcile gate (FR-012) against the real [PhysicalPlayViewModel] +
 * [EmulatedBoard]: a board that drops out of BLE range mid-game and comes back. Proves the gate the
 * [PhysicalMsg.BoardConnected] arm arms on every (re)connect — auto-resume when the returning board
 * still matches the live position, the diagnostics→restore loop when an *offline* change made it
 * diverge, and the §1.7 / §6.2 invariant that **no accepted move is lost or saved across the
 * disconnect+reconnect window**. Sibling of [PhysicalResumeEndToEndTest] (same board-match seam, armed
 * on reconnect instead of on load) and [PhysicalRecoverEndToEndTest]; same harness (real auto-saver
 * over a [FakeGameJournal], emulator on `backgroundScope`, `StandardTestDispatcher` shared with
 * `parseDispatcher`).
 *
 * Fault injection follows `lessons.md`: an offline board change is staged with the disconnected-only
 * [EmulatedBoard.setOccupancy] (`disconnect → setOccupancy → connect`), and the occupancy is taken from
 * the live board rather than relying on the emulator default. Timing: GAME-mode steps settle with
 * [advanceUntilIdle] (no stream to spin); the reconnect burst settles with [runCurrent] so a mismatch's
 * armed ~10 Hz diagnostic stream cannot make a later [advanceUntilIdle] hang; a restore is delivered
 * with [tick]. The gate-clearing match stops the stream (`SetMode(GAME)`), so the post-reconnect move
 * settles with [advanceUntilIdle] again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalReconnectEndToEndTest {
    private val gameId = "game-reconnect"
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
    fun `a matching board auto-resumes on reconnect with no SetMode and no accepted move lost`() =
        runTest {
            val h = connectedGame()
            // One move is accepted before the board drops out of range.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            assertEquals(listOf("e4"), playing(h.viewModel).sanMoves)
            assertEquals(1, h.acceptedMoveCount())

            // Out of range, then back — the board never changed, so it still matches the live position.
            h.board.disconnect()
            advanceUntilIdle() // disconnected → no stream to spin
            assertTrue(playing(h.viewModel).paused, "acceptance pauses while the board is unreachable")
            h.board.connect()
            runCurrent() // settle the reconnect burst WITHOUT advancing the ~10 Hz diagnostic clock

            // Auto-reconnect-on-match: the gate cleared with no extra input, diagnostics never opened, and the
            // board never left GAME mode — so NOT a single SetMode was emitted (the clean-match path).
            val resumed = playing(h.viewModel)
            assertFalse(resumed.reconnectReconciling, "a matching board clears the reconnect gate")
            assertFalse(resumed.acceptanceBlocked, "play is re-enabled with no extra input")
            assertFalse(resumed.diagnosticsVisible, "a clean reconnect never opens diagnostics")
            assertTrue(
                h.recording.sent.none { it is BoardCommand.SetMode },
                "a clean reconnect leaves the board in GAME mode — no SetMode at all",
            )
            assertEquals(1, h.acceptedMoveCount(), "the reconnect itself accepts nothing")

            // Play resumes: Black's reply is accepted normally — nothing was lost across the window.
            h.board.quietMove(sq('e', 7), sq('e', 5))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            assertEquals(listOf("e4", "e5"), playing(h.viewModel).sanMoves)
            assertEquals(2, h.acceptedMoveCount(), "exactly the one post-reconnect move was journaled")
        }

    @Test
    fun `an offline change blocks acceptance on reconnect until the board is restored then resumes`() =
        runTest {
            val h = connectedGame()
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()
            assertEquals(1, h.acceptedMoveCount())
            val expected = h.board.occupancy // the live position the reconnect snapshot is checked against

            // Out of range; while disconnected a piece is knocked off (offline change); then back in range.
            h.board.disconnect()
            advanceUntilIdle()
            h.board.setOccupancy(expected and (1L shl sq('a', 1)).inv()) // a1 rook missing on return
            h.board.connect()
            runCurrent() // reconnect snapshot diverges ⇒ the gate holds + setupMismatch auto-opens DIAGNOSTIC

            val gated = playing(h.viewModel)
            assertTrue(gated.reconnectReconciling, "a mismatching reconnect holds the reconcile gate")
            assertTrue(gated.acceptanceBlocked, "acceptance stays blocked until the board is reconciled")
            assertTrue(gated.diagnosticsVisible, "the reconnect mismatch auto-opens the reed grid")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)),
                "a reconnect mismatch enters diagnostic mode",
            )
            assertEquals(1, h.acceptedMoveCount(), "a gated reconnect accepts nothing")

            // §1.7: no move can be saved during the reconcile window — a confirm only re-pulls a snapshot.
            h.board.pressButton(BoardButton.BLACK)
            runCurrent()
            assertTrue(playing(h.viewModel).reconnectReconciling, "a confirm can't advance while reconciling")
            assertEquals(1, h.acceptedMoveCount(), "still nothing accepted in the reconcile window")

            // Restore: place the a1 rook back. The diagnostic stream delivers the matching snapshot that clears
            // the gate — the same at-rest board-match seam the resume/recover loops use.
            h.board.place(sq('a', 1))
            tick()

            val restored = playing(h.viewModel)
            assertFalse(restored.reconnectReconciling, "restoring the board clears the reconcile gate")
            assertFalse(restored.acceptanceBlocked)
            assertFalse(restored.diagnosticsVisible, "the grid closes once the board matches")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.GAME)),
                "a verified reconcile exits diagnostic mode (one SetMode(GAME) on the shown→hidden edge)",
            )
            assertEquals(1, h.acceptedMoveCount(), "nothing accepted across the whole reconnect window")

            // Play resumes from the exact pre-disconnect position: Black's reply is accepted, none lost.
            h.board.quietMove(sq('e', 7), sq('e', 5))
            h.board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()
            assertEquals(listOf("e4", "e5"), playing(h.viewModel).sanMoves)
            assertEquals(2, h.acceptedMoveCount(), "exactly the one post-restore move was journaled")
        }

    /** A fresh PHYSICAL game at the start position, connected with the ViewModel subscribed first. */
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
        val recording = RecordingBoardConnection(board)
        val viewModel = PhysicalPlayViewModel(gameId, repository, saver, recording, parseDispatcher = dispatcher)
        advanceUntilIdle()
        board.connect()
        advanceUntilIdle()
        return Harness(viewModel, board, recording, journal)
    }

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
