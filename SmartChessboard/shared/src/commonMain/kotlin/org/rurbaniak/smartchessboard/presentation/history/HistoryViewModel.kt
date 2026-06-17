package org.rurbaniak.smartchessboard.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

class HistoryViewModel(
    private val gamesRepository: GamesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

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
