package org.rurbaniak.smartchessboard.presentation.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.pgn.ReplayGame
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.chess.toFen
import org.rurbaniak.smartchessboard.domain.eval.EvalOutcome
import org.rurbaniak.smartchessboard.domain.eval.EvalRepository
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import kotlin.coroutines.cancellation.CancellationException

/** Per-ply evaluation state shown while analysis is enabled (FR-017). */
sealed interface PlyEvalState {
    data object Loading : PlyEvalState

    data class Evaluated(
        /** Centipawn score, White POV; null when the position is a forced mate. */
        val evalCp: Int?,
        /** Forced-mate distance, White-POV signed; null unless forced. */
        val mate: Int?,
        /** Best move in UCI notation, e.g. "e2e4". */
        val bestMoveUci: String?,
        /** The provider that produced the eval — survives cache hits. */
        val source: String,
        val depth: Int?,
        /** True when served from the shared cache. */
        val cached: Boolean = false,
    ) : PlyEvalState

    /** No provider knows this position — a stable answer, not an error (no retry). */
    data object NoEval : PlyEvalState

    /** Rate limit / outage / network failure — retryable. */
    data class Unavailable(
        val retryAfterSeconds: Int?,
    ) : PlyEvalState

    /** Checkmate/stalemate on the board — labeled locally, never sent to a provider. */
    data class Terminal(
        val status: GameStatus,
    ) : PlyEvalState
}

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
        val analysisEnabled: Boolean = false,
        /** Per-ply session cache of eval states; retained when analysis is toggled off. */
        val evals: Map<Int, PlyEvalState> = emptyMap(),
    ) : ReplayUiState {
        val position: Position get() = game.positions[currentPly]

        /** A truncation notice derives purely from the parsed game (contract §5.4 truncation). */
        val isTruncated: Boolean get() = game.truncation != null

        val canStepBack: Boolean get() = currentPly > 0
        val canStepForward: Boolean get() = currentPly < game.sanMoves.size

        /** Eval state for the viewed ply; null while analysis is off (cache stays warm underneath). */
        val currentEval: PlyEvalState? get() = if (analysisEnabled) evals[currentPly] else null
    }
}

/**
 * MVVM (per `lessons.md` — replay is a simple load-then-navigate-an-index screen, no MVI
 * justification): load the record, parse it once into a [ReplayGame], expose replay state and the
 * navigation intents. Opens at ply 0 (start position, per the slice's interview decision).
 *
 * Analysis (S-03): toggling on evaluates the viewed position on demand via [EvalRepository];
 * results cache per ply for the screen's lifetime, ply navigation cancels the in-flight request,
 * and terminal positions resolve locally via [status] without touching the repository.
 */
class ReplayViewModel(
    private val gameId: String,
    private val gamesRepository: GamesRepository,
    private val evalRepository: EvalRepository,
    // Injected so tests can pin parsing to a shared TestDispatcher; in production the default keeps
    // the per-ply legalMoves enumeration off the main thread (Android-Developers coroutines guidance:
    // never hardcode a dispatcher inside a withContext — inject it).
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReplayUiState>(ReplayUiState.Loading)
    val uiState: StateFlow<ReplayUiState> = _uiState.asStateFlow()

    private var evalJob: Job? = null

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        evalJob?.cancel()
        _uiState.value = ReplayUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                try {
                    val record = gamesRepository.getGame(gameId)
                    val game = withContext(parseDispatcher) { parsePgn(record.pgn) }
                    ReplayUiState.Loaded(game = game, currentPly = 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
                    ReplayUiState.Error
                }
        }
    }

    fun goToStart() = moveTo(0)

    fun stepBack() = moveTo(currentPly() - 1)

    fun stepForward() = moveTo(currentPly() + 1)

    fun goToEnd() = moveTo(Int.MAX_VALUE)

    fun jumpTo(ply: Int) = moveTo(ply)

    fun toggleAnalysis() {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        val enabled = !state.analysisEnabled
        if (!enabled) evalJob?.cancel()
        _uiState.value = state.copy(analysisEnabled = enabled)
        if (enabled) evaluateIfUncached(state.currentPly)
    }

    /** Recovery path for [PlyEvalState.Unavailable] — refetches the viewed ply only. */
    fun retryEval() {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        if (!state.analysisEnabled) return
        evaluate(state.currentPly)
    }

    private fun currentPly(): Int = (_uiState.value as? ReplayUiState.Loaded)?.currentPly ?: 0

    /** Single clamp point: every intent funnels here so navigation can never leave `0..lastPly`. */
    private fun moveTo(ply: Int) {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        val clamped = ply.coerceIn(0, state.game.sanMoves.size)
        if (clamped != state.currentPly) {
            _uiState.value = state.copy(currentPly = clamped)
            if (state.analysisEnabled) evaluateIfUncached(clamped)
        }
    }

    /**
     * Resolved states (Evaluated / NoEval / Terminal) are the session cache and never refetch.
     * Null, stale Loading (left behind by a cancel), and Unavailable all (re)fetch.
     */
    private fun evaluateIfUncached(ply: Int) {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        when (state.evals[ply]) {
            is PlyEvalState.Evaluated, PlyEvalState.NoEval, is PlyEvalState.Terminal -> Unit
            else -> evaluate(ply)
        }
    }

    private fun evaluate(ply: Int) {
        val state = _uiState.value as? ReplayUiState.Loaded ?: return
        evalJob?.cancel()
        val position = state.game.positions[ply]
        val terminal = status(position)
        if (terminal == GameStatus.Checkmate || terminal == GameStatus.Stalemate) {
            setEval(ply, PlyEvalState.Terminal(terminal))
            return
        }
        setEval(ply, PlyEvalState.Loading)
        val fen = position.toFen()
        evalJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        evalRepository.evaluate(fen)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Throwable, not Exception: on wasm a failed fetch surfaces as kotlin.Error
                        // (ktor JsError), which would otherwise crash the screen. 401 lands here too;
                        // the global auth gate owns session expiry, so the panel degrades to retryable.
                        EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null)
                    }
                setEval(ply, outcome.toPlyEvalState())
            }
    }

    private fun setEval(
        ply: Int,
        evalState: PlyEvalState,
    ) {
        _uiState.update { state ->
            if (state is ReplayUiState.Loaded) state.copy(evals = state.evals + (ply to evalState)) else state
        }
    }
}

private fun EvalOutcome.toPlyEvalState(): PlyEvalState =
    when (this) {
        is EvalOutcome.Evaluated -> {
            PlyEvalState.Evaluated(
                evalCp = evalCp,
                mate = mate,
                bestMoveUci = bestMoveUci,
                source = source,
                depth = depth,
                cached = cached,
            )
        }

        EvalOutcome.NoEval -> {
            PlyEvalState.NoEval
        }

        is EvalOutcome.TemporarilyUnavailable -> {
            PlyEvalState.Unavailable(retryAfterSeconds)
        }
    }
