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
 * S-07 end-to-end recover loop against the real [PhysicalPlayViewModel] + [EmulatedBoard]: drive
 * reject → paused gate → restore → verified → retry → accept for both `ILLEGAL` and `INCONSISTENT`,
 * proving the §6.2 guarantee that **no move is journaled until the retried legal move**. Sibling of
 * `PhysicalCaptureEndToEndTest`; same harness (real auto-saver over a [FakeGameJournal], emulator on
 * `backgroundScope`, `StandardTestDispatcher` shared with `parseDispatcher`).
 *
 * Determinism: the `ILLEGAL` / `INCONSISTENT` fork (`PhysicalPlayReducer.confirm()`) is decided by the
 * `latestOccupancy` snapshot held at the confirm reduce. Each test therefore **asserts that fork input
 * explicitly** right before the rejecting confirm (stale-equal-to-start ⇒ ILLEGAL; fresh-and-different
 * ⇒ INCONSISTENT), so the category never rides on dispatcher interleaving. During recovery the emulator
 * streams ~10 Hz diagnostic snapshots on `backgroundScope`; the window is driven with [runCurrent]
 * (settle immediate work, observe **no** tick) and [tick] (advance exactly one snapshot) so every
 * `latestOccupancy` the assertions read is the one this test put there. `backgroundScope` work is
 * auto-cancelled at test end, and the stream is stopped via `SetMode(GAME)` on restore, so the closing
 * `advanceUntilIdle()` cannot hang.
 *
 * §6.2 evidence is asserted **before** the reject-category assertion at every window point, so the
 * no-save guarantee is never gated behind the category discrimination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalRecoverEndToEndTest {
    private val gameId = "game-recover"
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
    fun `illegal sequence pauses then recovers on restore and a legal retry saves exactly one move`() =
        runTest {
            val h = connectedGame()
            val expected = h.board.occupancy // the start-position occupancy the reducer verifies against
            assertTrue(playing(h.viewModel).sanMoves.isEmpty())
            assertFalse(playing(h.viewModel).recovering)

            // Drive an illegal delta: e2 → e5 (a 3-square pawn jump) resolves to no legal move. GAME mode
            // does not stream snapshots, so latestOccupancy stays the stale on-connect snapshot.
            h.board.quietMove(sq('e', 2), sq('e', 5))
            runCurrent()
            // Pin the fork input: the board diverged but no snapshot of it has arrived, so latestOccupancy
            // is still the start occupancy ⇒ the fork must take the ILLEGAL arm (inconsistent == false).
            assertEquals(
                expected,
                playing(h.viewModel).latestOccupancy,
                "the ILLEGAL fork must see a stale-equal snapshot",
            )

            h.board.pressButton(BoardButton.WHITE)
            runCurrent()

            // §6.2 + gate first — these hold whatever reject category was produced.
            val rejected = playing(h.viewModel)
            assertTrue(rejected.recovering, "an illegal confirm pauses the game")
            assertTrue(rejected.acceptanceBlocked, "acceptance is blocked while recovering")
            assertTrue(rejected.sanMoves.isEmpty(), "no move advances")
            // saveLog always carries a dirty=false reconcile/seed row; the §6.2 invariant is specifically
            // "no dirty (accepted-move) write" — acceptMove/finishGame are the only dirty writers.
            assertTrue(rejected.journalHasNoAcceptedMove(h), "a rejected move journals nothing")
            // The category itself, asserted separately.
            assertEquals(RejectionReason.ILLEGAL, rejected.rejection)
            // The diverged board's post-reject snapshot auto-opens the grid and enters diagnostic mode.
            assertTrue(rejected.diagnosticsVisible, "the diverged board auto-opens the reed grid")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)),
                "recovery enters diagnostic mode",
            )

            // A second confirm while recovering is a no-op: still paused, still nothing accepted.
            h.board.pressButton(BoardButton.WHITE)
            runCurrent()
            assertTrue(playing(h.viewModel).recovering, "acceptance stays blocked until the board is restored")
            assertTrue(playing(h.viewModel).journalHasNoAcceptedMove(h), "a second confirm commits nothing")

            // Restore: lift the misplaced pawn back to e2. These lift/place are restoration moves, not a
            // new move (accumulate is a no-op while recovering); the snapshot, not the deltas, verifies it.
            h.board.lift(sq('e', 5))
            h.board.place(sq('e', 2))
            runCurrent()
            assertTrue(playing(h.viewModel).recovering, "lift/place alone don't clear the gate — the snapshot does")

            tick() // one ~10 Hz diagnostic snapshot of the now-restored board

            val restored = playing(h.viewModel)
            assertFalse(restored.recovering, "an exact occupancy match clears the recovery gate")
            assertEquals(null, restored.rejection)
            assertFalse(restored.diagnosticsVisible, "the grid closes once the board is restored")
            assertTrue(
                h.recording.sent.contains(BoardCommand.SetMode(BoardMode.GAME)),
                "a verified restore exits diagnostic mode",
            )
            assertTrue(
                restored.journalHasNoAcceptedMove(h),
                "still nothing accepted across the whole reject→restore window",
            )

            // Legal retry: e2 → e4 commits exactly one move. The stream stopped on restore, so
            // advanceUntilIdle is safe again and flushes the §6.2 write + the cloud sync.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(listOf("e4"), playing(h.viewModel).sanMoves)
            assertEquals(1, h.acceptedMoveCount(), "exactly one accepted move was journaled across the whole loop")
        }

    @Test
    fun `inconsistent board pauses then recovers on restore and a legal retry saves exactly one move`() =
        runTest {
            val h = connectedGame()
            // The expected occupancy the reducer verifies against is the start position — the board's own
            // occupancy before any move (== positions.last().toOccupancy()).
            val expected = h.board.occupancy
            val phantom = expected or (1L shl sq('a', 5)) // start + an extra piece no legal move can produce

            // Fabricate an inconsistent board. setOccupancy is disconnected-only; the reconnect snapshot
            // reveals the divergence.
            h.board.disconnect()
            advanceUntilIdle() // no stream yet — safe
            h.board.setOccupancy(phantom)
            h.board.connect()
            runCurrent() // reconnect snapshot diverges ⇒ setupMismatch auto-opens DIAGNOSTIC (stream armed here)

            val diverged = playing(h.viewModel)
            assertTrue(diverged.setupMismatch, "the reconnect snapshot reveals the phantom piece")
            assertFalse(diverged.recovering, "a setup mismatch is not yet a rejection")
            assertTrue(diverged.journalHasNoAcceptedMove(h), "a setup mismatch alone accepts nothing")
            assertTrue(h.recording.sent.contains(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)))

            // Drive an illegal delta while the board is inconsistent.
            h.board.quietMove(sq('e', 2), sq('e', 5))
            runCurrent()
            // Pin the fork input: a fresh snapshot of the diverged board is present, so latestOccupancy
            // differs from the expected position ⇒ the fork must take the INCONSISTENT arm.
            assertTrue(
                playing(h.viewModel).latestOccupancy != expected,
                "the INCONSISTENT fork must see a fresh snapshot that disagrees with the expected position",
            )

            h.board.pressButton(BoardButton.WHITE)
            runCurrent()

            // §6.2 + gate first.
            val rejected = playing(h.viewModel)
            assertTrue(rejected.recovering, "an inconsistent confirm pauses the game")
            assertTrue(rejected.acceptanceBlocked)
            assertTrue(rejected.sanMoves.isEmpty())
            assertTrue(rejected.journalHasNoAcceptedMove(h), "a rejected move journals nothing")
            // The category — distinct from a plain ILLEGAL.
            assertEquals(RejectionReason.INCONSISTENT, rejected.rejection)

            // Restore: undo the delta and remove the phantom so occupancy == expected.
            h.board.lift(sq('e', 5))
            h.board.place(sq('e', 2))
            h.board.lift(sq('a', 5))
            runCurrent()
            assertTrue(playing(h.viewModel).recovering)

            tick()

            val restored = playing(h.viewModel)
            assertFalse(restored.recovering, "an exact occupancy match clears the gate for INCONSISTENT too")
            assertEquals(null, restored.rejection)
            assertTrue(h.recording.sent.contains(BoardCommand.SetMode(BoardMode.GAME)))
            assertTrue(
                restored.journalHasNoAcceptedMove(h),
                "still nothing accepted across the whole reject→restore window",
            )

            // Legal retry commits exactly one move.
            h.board.quietMove(sq('e', 2), sq('e', 4))
            h.board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(listOf("e4"), playing(h.viewModel).sanMoves)
            assertEquals(1, h.acceptedMoveCount())
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

    /** §6.2: no accepted-move (dirty) write has reached the journal yet. */
    private fun PhysicalPlayState.Playing.journalHasNoAcceptedMove(h: Harness): Boolean = h.acceptedMoveCount() == 0

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
