package org.rurbaniak.smartchessboard.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.rurbaniak.smartchessboard.domain.games.GameDeleter
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.presentation.history.DeletePromptState
import org.rurbaniak.smartchessboard.presentation.history.HistoryUiState
import org.rurbaniak.smartchessboard.presentation.history.HistoryViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private lateinit var repository: FakeGamesRepository
    private lateinit var journal: FakeGameJournal

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = FakeGamesRepository()
        journal = FakeGameJournal()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The real GameDeleter over the fakes (no deleter fake): the delete tests pin the VM state
    // machine plus the orchestrator's observable effects — cloud call, changes-driven refresh.
    private fun viewModel() = HistoryViewModel(repository, GameDeleter(gamesRepository = repository, journal = journal))

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
            val viewModel = viewModel()
            assertEquals(HistoryUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun emptyListResolvesToEmptyState() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(HistoryUiState.Empty, viewModel.uiState.value)
        }

    @Test
    fun nonEmptyListResolvesToLoadedPreservingRepositoryOrder() =
        runTest {
            val newest = game("g2", "2026-06-10T12:00:00+00:00")
            val oldest = game("g1", "2026-06-09T12:00:00+00:00")
            repository.games = listOf(newest, oldest)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(HistoryUiState.Loaded(listOf(newest, oldest)), viewModel.uiState.value)
        }

    @Test
    fun failureResolvesToErrorState() =
        runTest {
            repository.shouldFail = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(HistoryUiState.Error, viewModel.uiState.value)
        }

    @Test
    fun loadMapsANonExceptionThrowableToError() =
        runTest {
            // On wasm a fetch failure surfaces as kotlin.Error (Ktor JsError), not Exception; the VM
            // must still resolve to Error, not let it escape as an uncaught coroutine exception.
            repository.shouldFail = true
            repository.failure = Error("Fail to fetch")
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(HistoryUiState.Error, viewModel.uiState.value)
        }

    @Test
    fun retryAfterErrorReloads() =
        runTest {
            repository.shouldFail = true
            val viewModel = viewModel()
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

    @Test
    fun refreshWhileInitialLoadInFlightIsSkipped() =
        runTest {
            val viewModel = viewModel()
            // Still Loading (init load not advanced yet) → refresh must not fire a second fetch.
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(1, repository.listCalls)
        }

    @Test
    fun refreshReloadsTheListAfterReturn() =
        runTest {
            repository.games = listOf(game("g1", "2026-06-10T12:00:00+00:00"))
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(HistoryUiState.Loaded(repository.games), viewModel.uiState.value)

            // A new game was created/played while away; the next refresh surfaces it.
            val withNew = listOf(game("g2", "2026-06-11T12:00:00+00:00")) + repository.games
            repository.games = withNew
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(HistoryUiState.Loaded(withNew), viewModel.uiState.value)
            assertEquals(2, repository.listCalls)
        }

    @Test
    fun repositoryChangeSignalReloadsTheListWithoutAScreenEvent() =
        runTest {
            repository.games = listOf(game("g1", "2026-06-10T12:00:00+00:00"))
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(1, repository.listCalls)

            // A game finished elsewhere (the Play screen) signals the repository changed; the
            // retained History VM re-fetches on the signal — no lifecycle/composition event needed.
            val withNew = listOf(game("g2", "2026-06-11T12:00:00+00:00")) + repository.games
            repository.games = withNew
            repository.finishGame("g2", GameResult.WHITE, "1-0")
            advanceUntilIdle()

            assertEquals(2, repository.listCalls)
            assertEquals(HistoryUiState.Loaded(withNew), viewModel.uiState.value)
        }

    @Test
    fun refreshFailureKeepsTheCurrentList() =
        runTest {
            val loaded = listOf(game("g1", "2026-06-10T12:00:00+00:00"))
            repository.games = loaded
            val viewModel = viewModel()
            advanceUntilIdle()

            repository.shouldFail = true
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                HistoryUiState.Loaded(loaded),
                viewModel.uiState.value,
                "a refresh failure must not blank the list",
            )
        }

    @Test
    fun requestDeleteOpensThePromptWithoutDeleting() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)

            assertEquals(DeletePromptState(g), viewModel.deletePrompt.value)
            assertTrue(repository.deleteGameCalls.isEmpty(), "requesting the prompt must not delete")
        }

    @Test
    fun dismissDeleteClosesThePromptAndDeletesNothing() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            viewModel.dismissDelete()
            advanceUntilIdle()

            assertNull(viewModel.deletePrompt.value)
            assertTrue(repository.deleteGameCalls.isEmpty(), "cancel must leave the game untouched")
        }

    @Test
    fun confirmDeleteSuccessClosesThePromptAndTheListDropsTheRow() =
        runTest {
            val keep = game("g2", "2026-06-11T12:00:00+00:00")
            val doomed = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(keep, doomed)
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(1, repository.listCalls)

            viewModel.requestDelete(doomed)
            // The cloud row disappears with the delete; the changes-driven refresh re-fetches this.
            repository.games = listOf(keep)
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("g1"), repository.deleteGameCalls)
            assertNull(viewModel.deletePrompt.value, "the prompt closes on success")
            assertEquals(2, repository.listCalls, "the changes signal drives one refresh")
            assertEquals(HistoryUiState.Loaded(listOf(keep)), viewModel.uiState.value)
        }

    @Test
    fun confirmDeleteIsIdempotentWhileInFlight() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            viewModel.confirmDelete()
            assertEquals(DeletePromptState(g, deleting = true), viewModel.deletePrompt.value)
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("g1"), repository.deleteGameCalls, "a second confirm while in flight is ignored")
            assertNull(viewModel.deletePrompt.value)
        }

    @Test
    fun dismissDeleteWhileDeletingIsIgnored() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            viewModel.confirmDelete()
            viewModel.dismissDelete()

            assertEquals(
                DeletePromptState(g, deleting = true),
                viewModel.deletePrompt.value,
                "dismiss must not close the dialog mid-delete",
            )
            advanceUntilIdle()
            assertNull(viewModel.deletePrompt.value)
        }

    @Test
    fun confirmDeleteFailureKeepsThePromptInFailedState() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            repository.shouldFail = true
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(DeletePromptState(g, deleting = false, failed = true), viewModel.deletePrompt.value)
            assertEquals(1, repository.deleteGameCalls.size, "the delete was attempted once")
            assertEquals(
                HistoryUiState.Loaded(listOf(g)),
                viewModel.uiState.value,
                "a failed delete must leave the list untouched",
            )
        }

    @Test
    fun retryAfterFailedDeleteSucceeds() =
        runTest {
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            repository.shouldFail = true
            viewModel.confirmDelete()
            advanceUntilIdle()
            assertEquals(DeletePromptState(g, failed = true), viewModel.deletePrompt.value)

            repository.shouldFail = false
            repository.games = emptyList()
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("g1", "g1"), repository.deleteGameCalls)
            assertNull(viewModel.deletePrompt.value)
            assertEquals(HistoryUiState.Empty, viewModel.uiState.value)
        }

    @Test
    fun confirmDeleteMapsANonExceptionThrowableToFailedState() =
        runTest {
            // On wasm a fetch failure surfaces as kotlin.Error (Ktor JsError), not Exception; the VM
            // must land in the failed prompt state, not crash with an uncaught coroutine exception.
            val g = game("g1", "2026-06-10T12:00:00+00:00")
            repository.games = listOf(g)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.requestDelete(g)
            repository.shouldFail = true
            repository.failure = Error("Fail to fetch")
            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(DeletePromptState(g, deleting = false, failed = true), viewModel.deletePrompt.value)
        }
}
