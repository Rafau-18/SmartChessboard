package org.rurbaniak.smartchessboard.presentation.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.pgn.ReplayGame
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import kotlin.coroutines.cancellation.CancellationException

sealed interface ReplayUiState {
    data object Loading : ReplayUiState

    /** Retryable, same convention as History. */
    data object Error : ReplayUiState

    /**
     * [currentPly] is the index into [ReplayGame.positions] currently shown — it ranges over
     * `0..game.sanMoves.size` (ply 0 = start position). For a truncated game `sanMoves` holds only
     * the moves that resolved, so navigation is naturally clamped to the replayable range.
     */
    data class Loaded(
        val game: ReplayGame,
        val currentPly: Int,
    ) : ReplayUiState {
        val position: Position get() = game.positions[currentPly]

        /** A truncation notice derives purely from the parsed game (contract §5.4 truncation). */
        val isTruncated: Boolean get() = game.truncation != null

        val canStepBack: Boolean get() = currentPly > 0
        val canStepForward: Boolean get() = currentPly < game.sanMoves.size
    }
}

/**
 * MVVM (per `lessons.md` — replay is a simple load-then-navigate-an-index screen, no MVI
 * justification): load the record, parse it once into a [ReplayGame], expose replay state and the
 * navigation intents. Opens at ply 0 (start position, per the slice's interview decision).
 */
class ReplayViewModel(
    private val gameId: String,
    private val gamesRepository: GamesRepository,
    // Injected so tests can pin parsing to a shared TestDispatcher; in production the default keeps
    // the per-ply legalMoves enumeration off the main thread (Android-Developers coroutines guidance:
    // never hardcode a dispatcher inside a withContext — inject it).
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReplayUiState>(ReplayUiState.Loading)
    val uiState: StateFlow<ReplayUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        _uiState.value = ReplayUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                try {
                    val record = gamesRepository.getGame(gameId)
                    val game = withContext(parseDispatcher) { parsePgn(record.pgn) }
                    ReplayUiState.Loaded(game = game, currentPly = 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ReplayUiState.Error
                }
        }
    }

    fun goToStart() = moveTo(0)

    fun stepBack() = moveTo(currentPly() - 1)

    fun stepForward() = moveTo(currentPly() + 1)

    fun goToEnd() = moveTo(Int.MAX_VALUE)

    fun jumpTo(ply: Int) = moveTo(ply)

    private fun currentPly(): Int = (_uiState.value as? ReplayUiState.Loaded)?.currentPly ?: 0

    /** Single clamp point: every intent funnels here so navigation can never leave `0..lastPly`. */
    private fun moveTo(ply: Int) {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        val clamped = ply.coerceIn(0, state.game.sanMoves.size)
        if (clamped != state.currentPly) {
            _uiState.value = state.copy(currentPly = clamped)
        }
    }
}
