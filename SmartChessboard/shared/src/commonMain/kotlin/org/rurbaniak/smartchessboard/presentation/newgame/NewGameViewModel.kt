package org.rurbaniak.smartchessboard.presentation.newgame

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import kotlin.coroutines.cancellation.CancellationException

/** Schema defaults applied when a label field is left blank (contract §2.2). */
private const val DEFAULT_WHITE_LABEL = "White"
private const val DEFAULT_BLACK_LABEL = "Black"

/**
 * [creating] gates the form while the INSERT is in flight; [failed] surfaces a retryable error
 * (creation requires connectivity — interview decision). [createdGameId] is set once on success
 * so the screen can hand the new id to navigation, then cleared via [onNavigated].
 */
data class NewGameUiState(
    val creating: Boolean = false,
    val failed: Boolean = false,
    val createdGameId: String? = null,
    /** The mode of the just-created game, so the screen routes physical → physical screen, digital → Play. */
    val createdGameMode: GameMode? = null,
)

/**
 * MVVM creation form (per `lessons.md`): the minimal two-label "new digital game" flow. Labels
 * default to the schema defaults when blank; on a successful INSERT the new game id is exposed for
 * navigation to the Play screen.
 */
class NewGameViewModel(
    private val gamesRepository: GamesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewGameUiState())
    val uiState: StateFlow<NewGameUiState> = _uiState.asStateFlow()

    fun create(
        whiteLabel: String,
        blackLabel: String,
        mode: GameMode,
    ) {
        if (_uiState.value.creating) return
        _uiState.update { it.copy(creating = true, failed = false) }
        viewModelScope.launch {
            _uiState.value =
                try {
                    val game =
                        gamesRepository.createGame(
                            whiteLabel = whiteLabel.ifBlank { DEFAULT_WHITE_LABEL },
                            blackLabel = blackLabel.ifBlank { DEFAULT_BLACK_LABEL },
                            mode = mode,
                        )
                    NewGameUiState(
                        creating = false,
                        failed = false,
                        createdGameId = game.id,
                        createdGameMode = game.mode,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
                    NewGameUiState(creating = false, failed = true, createdGameId = null)
                }
        }
    }

    /** Clears the one-shot navigation signal once the screen has consumed it. */
    fun onNavigated() {
        _uiState.update { it.copy(createdGameId = null, createdGameMode = null) }
    }
}
