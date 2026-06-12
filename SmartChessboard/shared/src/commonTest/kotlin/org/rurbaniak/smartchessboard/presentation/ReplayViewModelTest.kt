package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.presentation.replay.ReplayUiState
import org.rurbaniak.smartchessboard.presentation.replay.ReplayViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayViewModelTest {
    private lateinit var repository: FakeGamesRepository

    // Four-ply opening; sanMoves.size == 4, positions.size == 5.
    private val openingPgn = "1. e4 e5 2. Nf3 Nc6"

    // Nf6 is unreachable by either white knight on move 2 → parser truncates at ply index 2,
    // leaving sanMoves == [e4, e5] (a "truncated range" of two plies).
    private val illegalPgn = "1. e4 e5 2. Nf6"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = FakeGamesRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(
        id: String,
        pgn: String,
    ) = GameRecord(
        id = id,
        createdAt = "2026-06-12T12:00:00+00:00",
        mode = GameMode.DIGITAL,
        status = GameStatus.FINISHED,
        result = GameResult.WHITE,
        whiteLabel = "White",
        blackLabel = "Black",
        pgn = pgn,
    )

    private fun loadedViewModel(
        id: String,
        pgn: String,
    ): ReplayViewModel {
        repository.records = mapOf(id to record(id, pgn))
        val viewModel = ReplayViewModel(gameId = id, gamesRepository = repository)
        return viewModel
    }

    private fun loaded(state: ReplayUiState): ReplayUiState.Loaded = assertIs<ReplayUiState.Loaded>(state)

    @Test
    fun startsInLoadingState() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            assertEquals(ReplayUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun loadSuccessResolvesToLoadedAtPlyZero() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertEquals(0, state.currentPly)
            assertEquals(Position.start(), state.position)
            assertEquals(4, state.game.sanMoves.size)
            assertFalse(state.canStepBack)
            assertTrue(state.canStepForward)
            assertFalse(state.isTruncated)
        }

    @Test
    fun repositoryFailureResolvesToErrorThenRetryReloads() =
        runTest {
            repository.records = mapOf("g1" to record("g1", openingPgn))
            repository.shouldFail = true
            val viewModel = ReplayViewModel(gameId = "g1", gamesRepository = repository)
            advanceUntilIdle()
            assertEquals(ReplayUiState.Error, viewModel.uiState.value)

            repository.shouldFail = false
            viewModel.retry()
            assertEquals(ReplayUiState.Loading, viewModel.uiState.value)
            advanceUntilIdle()
            assertIs<ReplayUiState.Loaded>(viewModel.uiState.value)
        }

    @Test
    fun stepForwardAndBackMoveOnePly() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.stepForward()
            assertEquals(1, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepForward()
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepBack()
            assertEquals(1, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun navigationClampsAtBothBounds() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.stepBack()
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)

            viewModel.goToEnd()
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)
            viewModel.stepForward()
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)

            viewModel.jumpTo(99)
            assertEquals(4, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(-5)
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(2)
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun goToEndLandsOnFinalPosition() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.goToEnd()
            val state = loaded(viewModel.uiState.value)
            assertEquals(state.game.positions.last(), state.position)
            assertFalse(state.canStepForward)
            assertTrue(state.canStepBack)
        }

    @Test
    fun goToStartReturnsToPlyZero() =
        runTest {
            val viewModel = loadedViewModel("g1", openingPgn)
            advanceUntilIdle()

            viewModel.goToEnd()
            viewModel.goToStart()
            assertEquals(0, loaded(viewModel.uiState.value).currentPly)
        }

    @Test
    fun truncatedGameExposesNoticeAndClampsToTruncatedRange() =
        runTest {
            val viewModel = loadedViewModel("g1", illegalPgn)
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertTrue(state.isTruncated)
            assertEquals(2, state.game.sanMoves.size)

            viewModel.goToEnd()
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            viewModel.jumpTo(10)
            assertEquals(2, loaded(viewModel.uiState.value).currentPly)
            assertFalse(loaded(viewModel.uiState.value).canStepForward)
        }

    @Test
    fun emptyPgnIsLoadedWithSinglePositionAndDisabledForward() =
        runTest {
            val viewModel = loadedViewModel("g1", "")
            advanceUntilIdle()

            val state = loaded(viewModel.uiState.value)
            assertEquals(0, state.currentPly)
            assertTrue(state.game.sanMoves.isEmpty())
            assertEquals(Position.start(), state.position)
            assertFalse(state.canStepForward)
            assertFalse(state.canStepBack)
        }
}
