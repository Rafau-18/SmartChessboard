package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnMeta
import org.rurbaniak.smartchessboard.domain.chess.pgn.writePgn
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.JournalEntry
import org.rurbaniak.smartchessboard.presentation.play.PlayUiState
import org.rurbaniak.smartchessboard.presentation.play.PlayViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.rurbaniak.smartchessboard.domain.games.GameStatus as RecordStatus

@OptIn(ExperimentalCoroutinesApi::class)
class PlayViewModelTest {
    private lateinit var repository: FakeGamesRepository
    private lateinit var journal: FakeGameJournal

    // One TestDispatcher drives Main (viewModelScope) and the injected resume-parse dispatcher, so
    // advanceUntilIdle() runs the off-Main parsePgn to completion (same pattern as ReplayViewModel).
    private lateinit var dispatcher: TestDispatcher

    // The header tags PlayViewModel derives from the record below — used to assert exact journaled
    // / uploaded PGN documents.
    private val vmMeta =
        PgnMeta(
            event = "Smart Chessboard",
            date = "2026.06.12",
            white = "White",
            black = "Black",
            result = "*",
            mode = "digital",
        )

    private fun pgn(vararg sans: String) = writePgn(vmMeta, sans.toList())

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeGamesRepository()
        journal = FakeGameJournal()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sq(
        file: Char,
        rank: Int,
    ): Int = (file - 'a') + 8 * (rank - 1)

    private fun record(
        id: String,
        pgn: String,
    ) = GameRecord(
        id = id,
        createdAt = "2026-06-12T12:00:00+00:00",
        mode = GameMode.DIGITAL,
        status = RecordStatus.IN_PROGRESS,
        result = null,
        whiteLabel = "White",
        blackLabel = "Black",
        pgn = pgn,
    )

    private fun playViewModel(
        id: String,
        pgn: String,
    ): PlayViewModel {
        repository.records = mapOf(id to record(id, pgn))
        val autoSaver = GameAutoSaver(gamesRepository = repository, journal = journal)
        return PlayViewModel(
            gameId = id,
            gamesRepository = repository,
            autoSaver = autoSaver,
            parseDispatcher = dispatcher,
        )
    }

    private fun playing(state: PlayUiState): PlayUiState.Playing = assertIs<PlayUiState.Playing>(state)

    // --- load ---

    @Test
    fun startsInLoading() =
        runTest {
            val viewModel = playViewModel("g1", "")
            assertEquals(PlayUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun loadResolvesToPlayingAtStartPosition() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            val state = playing(viewModel.uiState.value)
            assertTrue(state.sanMoves.isEmpty())
            assertEquals(1, state.positions.size)
            assertEquals(Color.WHITE, state.position.sideToMove)
            assertEquals(GameStatus.Ongoing, state.status)
            assertFalse(state.terminal)
            assertNull(state.selectedSquare)
        }

    @Test
    fun loadFailureResolvesToErrorThenRetryReloads() =
        runTest {
            repository.records = mapOf("g1" to record("g1", ""))
            repository.shouldFail = true
            val autoSaver = GameAutoSaver(gamesRepository = repository, journal = journal)
            val viewModel =
                PlayViewModel("g1", repository, autoSaver, parseDispatcher = dispatcher)
            advanceUntilIdle()
            assertEquals(PlayUiState.Error, viewModel.uiState.value)

            repository.shouldFail = false
            viewModel.retry()
            advanceUntilIdle()
            assertIs<PlayUiState.Playing>(viewModel.uiState.value)
        }

    @Test
    fun resumesFromMidGamePgn() =
        runTest {
            val viewModel = playViewModel("g1", "1. e4 e5 2. Nf3")
            advanceUntilIdle()

            val state = playing(viewModel.uiState.value)
            assertEquals(listOf("e4", "e5", "Nf3"), state.sanMoves)
            assertEquals(4, state.positions.size)
            assertEquals(Color.BLACK, state.position.sideToMove)
        }

    // --- selection ---

    @Test
    fun tapSelectsOwnPieceAndShowsLegalTargets() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))

            val state = playing(viewModel.uiState.value)
            assertEquals(sq('e', 2), state.selectedSquare)
            assertEquals(setOf(sq('e', 3), sq('e', 4)), state.targetSquares)
        }

    @Test
    fun reTapOnSelectionDeselects() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))
            viewModel.onSquareTap(sq('e', 2))

            val state = playing(viewModel.uiState.value)
            assertNull(state.selectedSquare)
            assertTrue(state.targetSquares.isEmpty())
        }

    @Test
    fun tapOnAnotherOwnPieceReselects() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))
            viewModel.onSquareTap(sq('a', 2))

            val state = playing(viewModel.uiState.value)
            assertEquals(sq('a', 2), state.selectedSquare)
            assertEquals(setOf(sq('a', 3), sq('a', 4)), state.targetSquares)
        }

    @Test
    fun tapOnEmptyNonTargetDeselects() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))
            viewModel.onSquareTap(sq('a', 6)) // empty, not a legal target of the e2 pawn

            assertNull(playing(viewModel.uiState.value).selectedSquare)
        }

    @Test
    fun tapOnOpponentPieceWithNoSelectionIsANoOp() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 7)) // black pawn, white to move

            assertNull(playing(viewModel.uiState.value).selectedSquare)
        }

    @Test
    fun tapOnEmptySquareWithNoSelectionIsANoOp() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()
            val before = playing(viewModel.uiState.value)

            viewModel.onSquareTap(sq('a', 6))

            assertEquals(before, playing(viewModel.uiState.value))
            assertTrue(repository.updatePgnCalls.isEmpty())
        }

    // --- move acceptance & §6.2 ordering ---

    @Test
    fun legalMoveIsJournaledBeforeCloudThenSynced() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))
            viewModel.onSquareTap(sq('e', 4))

            // The §6.2 gate: the move is durably journaled before any cloud call is made.
            assertEquals(JournalEntry(pgn("e4"), dirty = true), journal.entries["g1"])
            assertTrue(repository.updatePgnCalls.isEmpty(), "journal write must precede the cloud sync")

            val moved = playing(viewModel.uiState.value)
            assertEquals(listOf("e4"), moved.sanMoves)
            assertEquals(2, moved.positions.size)
            assertEquals(Color.BLACK, moved.position.sideToMove)
            assertNull(moved.selectedSquare)
            assertTrue(moved.syncPending)

            advanceUntilIdle()
            assertEquals(listOf("g1" to pgn("e4")), repository.updatePgnCalls)
            assertFalse(playing(viewModel.uiState.value).syncPending)
        }

    @Test
    fun syncFailureKeepsSyncPendingThenLaterMoveRecovers() =
        runTest {
            repository.updatePgnFailures = Int.MAX_VALUE
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()

            viewModel.onSquareTap(sq('e', 2))
            viewModel.onSquareTap(sq('e', 4))
            advanceUntilIdle()

            assertEquals(4, repository.updatePgnCalls.size, "initial attempt + three retries")
            assertTrue(journal.entries.getValue("g1").dirty)
            assertTrue(playing(viewModel.uiState.value).syncPending)

            // Connectivity returns; the next accepted move's sync flushes the journal.
            repository.updatePgnFailures = 0
            viewModel.onSquareTap(sq('e', 7))
            viewModel.onSquareTap(sq('e', 5))
            advanceUntilIdle()

            assertFalse(journal.entries.getValue("g1").dirty)
            assertFalse(playing(viewModel.uiState.value).syncPending)
        }

    // --- promotion ---

    private val promotionPgn = "1. e4 d5 2. exd5 c6 3. dxc6 e5 4. cxb7 Qd6"

    @Test
    fun promotionTargetOpensPickerAndPickCompletesTheMove() =
        runTest {
            val viewModel = playViewModel("g1", promotionPgn)
            advanceUntilIdle()
            val before = playing(viewModel.uiState.value)
            assertEquals(Color.WHITE, before.position.sideToMove)

            viewModel.onSquareTap(sq('b', 7)) // the promoting pawn
            viewModel.onSquareTap(sq('a', 8)) // capture-promotion target → picker, no move yet

            val pending = playing(viewModel.uiState.value).pendingPromotion
            assertEquals(sq('b', 7), pending?.from)
            assertEquals(sq('a', 8), pending?.to)
            assertEquals(Color.WHITE, pending?.color)
            assertEquals(
                before.sanMoves,
                playing(viewModel.uiState.value).sanMoves,
                "no move saved until the piece is chosen",
            )

            viewModel.onPromotionPick(PieceType.QUEEN)

            val after = playing(viewModel.uiState.value)
            assertNull(after.pendingPromotion)
            assertEquals(before.sanMoves.size + 1, after.sanMoves.size)
            assertTrue(after.sanMoves.last().startsWith("bxa8=Q"), "got ${after.sanMoves.last()}")
            assertEquals(JournalEntry(pgn(*after.sanMoves.toTypedArray()), dirty = true), journal.entries["g1"])
        }

    @Test
    fun promotionDismissCancelsWithoutSavingAMove() =
        runTest {
            val viewModel = playViewModel("g1", promotionPgn)
            advanceUntilIdle()
            val before = playing(viewModel.uiState.value)

            viewModel.onSquareTap(sq('b', 7))
            viewModel.onSquareTap(sq('a', 8))
            assertEquals(sq('b', 7), playing(viewModel.uiState.value).pendingPromotion?.from)

            viewModel.onPromotionDismiss()

            val after = playing(viewModel.uiState.value)
            assertNull(after.pendingPromotion)
            assertNull(after.selectedSquare)
            assertEquals(before.sanMoves, after.sanMoves)
            assertTrue(repository.updatePgnCalls.isEmpty(), "a dismissed promotion saves nothing")
        }

    // --- terminal ---

    @Test
    fun terminalPositionBlocksFurtherInput() =
        runTest {
            // Fool's mate — the loaded position is checkmate, White to move and mated.
            val viewModel = playViewModel("g1", "1. f3 e5 2. g4 Qh4#")
            advanceUntilIdle()

            val state = playing(viewModel.uiState.value)
            assertEquals(GameStatus.Checkmate, state.status)
            assertTrue(state.terminal)

            viewModel.onSquareTap(sq('e', 2)) // a white pawn still on the board — must be ignored
            assertNull(playing(viewModel.uiState.value).selectedSquare)
        }

    // --- reconcile through the VM ---

    @Test
    fun journalAheadOfCloudResumesFromJournal() =
        runTest {
            // A move was journaled locally but never confirmed to the cloud before the app died.
            journal.save("g1", pgn("e4", "e5"), dirty = true)
            val viewModel = playViewModel("g1", pgn("e4"))
            advanceUntilIdle()

            val state = playing(viewModel.uiState.value)
            assertEquals(listOf("e4", "e5"), state.sanMoves)
            // Reconcile flushed the ahead journal to the cloud on load.
            assertEquals(listOf("g1" to pgn("e4", "e5")), repository.updatePgnCalls)
        }

    @Test
    fun flipBoardTogglesOrientation() =
        runTest {
            val viewModel = playViewModel("g1", "")
            advanceUntilIdle()
            assertEquals(Color.WHITE, playing(viewModel.uiState.value).orientation)

            viewModel.flipBoard()
            assertEquals(Color.BLACK, playing(viewModel.uiState.value).orientation)
            viewModel.flipBoard()
            assertEquals(Color.WHITE, playing(viewModel.uiState.value).orientation)
        }
}
