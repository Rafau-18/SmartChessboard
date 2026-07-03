package org.rurbaniak.smartchessboard.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.domain.games.GameDeleter
import org.rurbaniak.smartchessboard.domain.games.GameSummary
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import kotlin.coroutines.cancellation.CancellationException

sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    data object Empty : HistoryUiState

    data class Loaded(
        val games: List<GameSummary>,
    ) : HistoryUiState

    data object Error : HistoryUiState
}

/**
 * The delete confirmation prompt for one game, driving the dialog on the History screen. Null (no
 * prompt) is the resting state; [deleting] disables the dialog while the delete is in flight;
 * [failed] keeps the dialog open with an error line and flips its confirm button to Retry.
 */
data class DeletePromptState(
    val game: GameSummary,
    val deleting: Boolean = false,
    val failed: Boolean = false,
)

class HistoryViewModel(
    private val gamesRepository: GamesRepository,
    private val gameDeleter: GameDeleter,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _deletePrompt = MutableStateFlow<DeletePromptState?>(null)
    val deletePrompt: StateFlow<DeletePromptState?> = _deletePrompt.asStateFlow()

    init {
        load()
        // The screen is retained across navigation, so init runs once. Re-fetch on a repository
        // change signal (a new or finished game) rather than on the screen re-entering composition
        // or a lifecycle resume — both diverge across Android / iOS / web. refresh() no-ops while
        // the first load is still in flight.
        viewModelScope.launch {
            gamesRepository.changes.collect { refresh() }
        }
    }

    fun retry() {
        load()
    }

    /** Opens the delete confirmation prompt for [game]. Nothing is deleted until [confirmDelete]. */
    fun requestDelete(game: GameSummary) {
        _deletePrompt.value = DeletePromptState(game)
    }

    /** Closes the prompt without deleting. Ignored while the delete is in flight. */
    fun dismissDelete() {
        if (_deletePrompt.value?.deleting == true) return
        _deletePrompt.value = null
    }

    /**
     * Performs the confirmed delete (also serves as Retry after a failure). Idempotent while in
     * flight. On success the prompt closes and the list refreshes itself via the repository
     * [GamesRepository.changes] signal; on failure the prompt stays open in the failed state.
     */
    fun confirmDelete() {
        val prompt = _deletePrompt.value ?: return
        if (prompt.deleting) return
        _deletePrompt.value = prompt.copy(deleting = true, failed = false)
        viewModelScope.launch {
            try {
                gameDeleter.delete(prompt.game.id)
                _deletePrompt.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
                _deletePrompt.value = prompt.copy(deleting = false, failed = true)
            }
        }
    }

    /**
     * Silent re-fetch driven by the screen re-entering composition (e.g. returning from a game just
     * created or played, which the cloud now lists). Unlike [load]/[retry] it does not flash the
     * Loading spinner and keeps the current list if the refresh fails — a transient error must not
     * blank a good list. Skipped while the first load is still in flight so it never double-fetches.
     */
    fun refresh() {
        if (_uiState.value is HistoryUiState.Loading) return
        viewModelScope.launch {
            try {
                val games = gamesRepository.listMyGames()
                _uiState.value = if (games.isEmpty()) HistoryUiState.Empty else HistoryUiState.Loaded(games)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep whatever is on screen — a refresh failure shouldn't clobber a loaded list.
                // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
            }
        }
    }

    private fun load() {
        _uiState.value = HistoryUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                try {
                    val games = gamesRepository.listMyGames()
                    if (games.isEmpty()) HistoryUiState.Empty else HistoryUiState.Loaded(games)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
                    HistoryUiState.Error
                }
        }
    }
}
