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
    }

    fun retry() {
        load()
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
                } catch (_: Exception) {
                    HistoryUiState.Error
                }
        }
    }
}
