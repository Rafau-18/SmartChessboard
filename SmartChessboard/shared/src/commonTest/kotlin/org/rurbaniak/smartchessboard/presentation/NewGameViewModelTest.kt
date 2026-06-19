package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.presentation.newgame.NewGameViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewGameViewModelTest {
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

    private fun newRecord(id: String) =
        GameRecord(
            id = id,
            createdAt = "2026-06-13T09:00:00+00:00",
            mode = GameMode.DIGITAL,
            status = GameStatus.IN_PROGRESS,
            result = null,
            whiteLabel = "Alice",
            blackLabel = "Bob",
            pgn = "",
        )

    @Test
    fun startsIdle() =
        runTest {
            val viewModel = NewGameViewModel(repository)
            assertEquals(false, viewModel.uiState.value.creating)
            assertFalse(viewModel.uiState.value.failed)
            assertNull(viewModel.uiState.value.createdGameId)
        }

    @Test
    fun createSuccessExposesNewGameId() =
        runTest {
            repository.createdGame = newRecord("game-9")
            val viewModel = NewGameViewModel(repository)

            viewModel.create("Alice", "Bob", GameMode.DIGITAL)
            assertTrue(viewModel.uiState.value.creating, "creating gate raised synchronously")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.creating)
            assertFalse(state.failed)
            assertEquals("game-9", state.createdGameId)
            assertEquals(listOf("Alice" to "Bob"), repository.createLabels)
        }

    @Test
    fun createPhysicalThreadsTheModeToTheRepositoryAndState() =
        runTest {
            repository.createdGame = newRecord("game-phys")
            val viewModel = NewGameViewModel(repository)

            viewModel.create("Alice", "Bob", GameMode.PHYSICAL)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("game-phys", state.createdGameId)
            assertEquals(GameMode.PHYSICAL, state.createdGameMode, "the screen routes on the created mode")
            assertEquals(listOf(GameMode.PHYSICAL), repository.createModes)
        }

    @Test
    fun blankLabelsFallBackToSchemaDefaults() =
        runTest {
            repository.createdGame = newRecord("game-1")
            val viewModel = NewGameViewModel(repository)

            viewModel.create("", "   ", GameMode.DIGITAL)
            advanceUntilIdle()

            assertEquals(listOf("White" to "Black"), repository.createLabels)
        }

    @Test
    fun createFailureIsRetryable() =
        runTest {
            repository.shouldFail = true
            val viewModel = NewGameViewModel(repository)

            viewModel.create("Alice", "Bob", GameMode.DIGITAL)
            advanceUntilIdle()
            var state = viewModel.uiState.value
            assertTrue(state.failed)
            assertFalse(state.creating)
            assertNull(state.createdGameId)

            repository.shouldFail = false
            repository.createdGame = newRecord("game-2")
            viewModel.create("Alice", "Bob", GameMode.DIGITAL)
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertFalse(state.failed)
            assertEquals("game-2", state.createdGameId)
        }

    @Test
    fun onNavigatedClearsTheOneShotSignal() =
        runTest {
            repository.createdGame = newRecord("game-3")
            val viewModel = NewGameViewModel(repository)
            viewModel.create("Alice", "Bob", GameMode.DIGITAL)
            advanceUntilIdle()
            assertEquals("game-3", viewModel.uiState.value.createdGameId)

            viewModel.onNavigated()

            assertNull(viewModel.uiState.value.createdGameId)
        }

    @Test
    fun secondCreateWhileCreatingIsIgnored() =
        runTest {
            repository.createdGame = newRecord("game-4")
            val viewModel = NewGameViewModel(repository)

            viewModel.create("Alice", "Bob", GameMode.DIGITAL)
            viewModel.create("Carol", "Dave", GameMode.DIGITAL) // ignored: a create is already in flight
            advanceUntilIdle()

            assertEquals(1, repository.createCalls)
            assertEquals(listOf("Alice" to "Bob"), repository.createLabels)
        }
}
