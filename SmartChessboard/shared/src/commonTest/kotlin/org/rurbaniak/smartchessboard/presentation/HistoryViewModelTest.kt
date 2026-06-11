package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.presentation.history.HistoryUiState
import org.rurbaniak.smartchessboard.presentation.history.HistoryViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private lateinit var repository: FakeGamesRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = FakeGamesRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun game(
        id: String,
        createdAt: String,
    ) = GameSummary(
        id = id,
        createdAt = createdAt,
        mode = GameMode.DIGITAL,
        status = GameStatus.FINISHED,
        result = GameResult.WHITE,
        whiteLabel = "White",
        blackLabel = "Black",
    )

    @Test
    fun startsInLoadingState() =
        runTest {
            val viewModel = HistoryViewModel(repository)
            assertEquals(HistoryUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun emptyListResolvesToEmptyState() =
        runTest {
            val viewModel = HistoryViewModel(repository)
            advanceUntilIdle()
            assertEquals(HistoryUiState.Empty, viewModel.uiState.value)
        }

    @Test
    fun nonEmptyListResolvesToLoadedPreservingRepositoryOrder() =
        runTest {
            val newest = game("g2", "2026-06-10T12:00:00+00:00")
            val oldest = game("g1", "2026-06-09T12:00:00+00:00")
            repository.games = listOf(newest, oldest)

            val viewModel = HistoryViewModel(repository)
            advanceUntilIdle()

            assertEquals(HistoryUiState.Loaded(listOf(newest, oldest)), viewModel.uiState.value)
        }

    @Test
    fun failureResolvesToErrorState() =
        runTest {
            repository.shouldFail = true
            val viewModel = HistoryViewModel(repository)
            advanceUntilIdle()
            assertEquals(HistoryUiState.Error, viewModel.uiState.value)
        }

    @Test
    fun retryAfterErrorReloads() =
        runTest {
            repository.shouldFail = true
            val viewModel = HistoryViewModel(repository)
            advanceUntilIdle()
            assertEquals(HistoryUiState.Error, viewModel.uiState.value)

            repository.shouldFail = false
            repository.games = listOf(game("g1", "2026-06-10T12:00:00+00:00"))
            viewModel.retry()
            assertEquals(HistoryUiState.Loading, viewModel.uiState.value)
            advanceUntilIdle()

            assertEquals(HistoryUiState.Loaded(repository.games), viewModel.uiState.value)
            assertEquals(2, repository.listCalls)
        }
}
