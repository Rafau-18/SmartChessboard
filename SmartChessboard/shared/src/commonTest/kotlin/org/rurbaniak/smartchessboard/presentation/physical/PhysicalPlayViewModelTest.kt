package org.rurbaniak.smartchessboard.presentation.physical

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.data.board.emulator.EmulatedBoard
import org.rurbaniak.smartchessboard.data.board.emulator.quietMove
import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameJournal
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.JournalEntry
import org.rurbaniak.smartchessboard.presentation.FakeGameJournal
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Integration of the pure reducer with the impure shell: drives a real EmulatedBoard through the
// BoardConnection port into the ViewModel, with the reused S-04 back half (real GameAutoSaver over a
// fake journal). Proves each correct confirm lands exactly one move in the canonical PGN with
// [Mode "physical"], that a forced journal-write failure is the §6.2 gate (MoveRejected, no advance),
// and that a wrong-side confirm saves nothing.
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalPlayViewModelTest {
    private val gameId = "game-physical"

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeGamesRepository

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeGamesRepository()
        repository.records =
            mapOf(
                gameId to
                    GameRecord(
                        id = gameId,
                        createdAt = "2026-06-19T10:00:00+00:00",
                        mode = GameMode.PHYSICAL,
                        status = GameStatus.IN_PROGRESS,
                        result = null,
                        whiteLabel = "White",
                        blackLabel = "Black",
                        pgn = "",
                    ),
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sq(
        file: Char,
        rank: Int,
    ): Int = squareOf(file - 'a', rank - 1)

    private fun playing(viewModel: PhysicalPlayViewModel): PhysicalPlayState.Playing =
        assertIs<PhysicalPlayState.Playing>(viewModel.state.value)

    @Test
    fun eachConfirmLandsExactlyOneMoveInThePhysicalPgn() =
        runTest {
            val journal = FakeGameJournal()
            val saver = GameAutoSaver(gamesRepository = repository, journal = journal)
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = kotlin.time.Duration.INFINITE)
            val viewModel = PhysicalPlayViewModel(gameId, repository, saver, board, parseDispatcher = dispatcher)

            // Subscribe-before-connect: let the ViewModel's collectors attach before the board burst.
            advanceUntilIdle()
            board.connect()
            advanceUntilIdle()

            assertTrue(!playing(viewModel).setupMismatch, "the start position must verify against the opening snapshot")

            // 1. e4 — confirmed with the White button.
            board.quietMove(sq('e', 2), sq('e', 4))
            board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            assertEquals(listOf("e4"), playing(viewModel).sanMoves)

            // 1... e5 — confirmed with the Black button.
            board.quietMove(sq('e', 7), sq('e', 5))
            board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()

            val state = playing(viewModel)
            assertEquals(listOf("e4", "e5"), state.sanMoves)
            assertEquals(3, state.positions.size)

            // The canonical record carries the physical mode tag and exactly the two confirmed moves.
            val stored = journal.entries.getValue(gameId).pgn
            assertTrue(stored.contains("[Mode \"physical\"]"), "stored PGN must tag the physical mode")
            assertTrue(stored.contains("1. e4 e5"), "stored PGN must hold both confirmed moves")
            // The cloud flush mirrored the same document.
            assertEquals(stored, repository.updatePgnCalls.last().second)
        }

    @Test
    fun aForcedJournalWriteFailureRejectsTheMoveAndDoesNotAdvance() =
        runTest {
            // The §6.2 gate: acceptMove's durable write throws, so the move must never count as accepted.
            val saver = GameAutoSaver(gamesRepository = repository, journal = FailOnDirtySaveJournal())
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = kotlin.time.Duration.INFINITE)
            val viewModel = PhysicalPlayViewModel(gameId, repository, saver, board, parseDispatcher = dispatcher)

            advanceUntilIdle()
            board.connect()
            advanceUntilIdle()

            board.quietMove(sq('e', 2), sq('e', 4))
            board.pressButton(BoardButton.WHITE)
            advanceUntilIdle()

            val state = playing(viewModel)
            assertEquals(RejectionReason.SAVE_FAILED, state.rejection)
            assertEquals(1, state.positions.size, "a failed save must not advance the game")
            assertTrue(state.sanMoves.isEmpty())
        }

    @Test
    fun aWrongSideConfirmSavesNothing() =
        runTest {
            val journal = FakeGameJournal()
            val saver = GameAutoSaver(gamesRepository = repository, journal = journal)
            val board = EmulatedBoard(scope = backgroundScope, statusInterval = kotlin.time.Duration.INFINITE)
            val viewModel = PhysicalPlayViewModel(gameId, repository, saver, board, parseDispatcher = dispatcher)

            advanceUntilIdle()
            board.connect()
            advanceUntilIdle()

            // White to move, but the Black button is pressed: no move is accepted.
            board.quietMove(sq('e', 2), sq('e', 4))
            board.pressButton(BoardButton.BLACK)
            advanceUntilIdle()

            assertTrue(playing(viewModel).sanMoves.isEmpty())
            // Only the reconcile seed (dirty = false) was written; no accepted-move (dirty = true) save.
            assertTrue(journal.saveLog.none { (_, _, dirty) -> dirty }, "no move was journaled")
        }
}

/** A journal that fails the durable accepted-move write (dirty = true) but allows the reconcile seed. */
private class FailOnDirtySaveJournal : GameJournal {
    private var entry: JournalEntry? = null

    override fun load(gameId: String): JournalEntry? = entry

    override fun save(
        gameId: String,
        pgn: String,
        dirty: Boolean,
        result: GameResult?,
    ) {
        if (dirty) throw IllegalStateException("disk full")
        entry = JournalEntry(pgn = pgn, dirty = false, result = result)
    }

    override fun markSynced(gameId: String) {
        entry = entry?.copy(dirty = false)
    }

    override fun clear(gameId: String) {
        entry = null
    }
}
