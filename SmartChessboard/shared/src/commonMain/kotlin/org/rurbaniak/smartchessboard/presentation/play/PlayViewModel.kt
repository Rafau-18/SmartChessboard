package org.rurbaniak.smartchessboard.presentation.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.MoveOutcome
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnMeta
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.pgn.sanForMove
import org.rurbaniak.smartchessboard.domain.chess.pgn.writePgn
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.chess.validate
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import kotlin.coroutines.cancellation.CancellationException

/** A pawn move awaiting the player's promotion choice (FR-006) — the move is not yet accepted. */
data class PendingPromotion(
    val from: Int,
    val to: Int,
    /** The moving color, so the picker shows the right pieces. */
    val color: Color,
)

sealed interface PlayUiState {
    data object Loading : PlayUiState

    /** Retryable — game load / reconcile failed (same convention as History/Replay). */
    data object Error : PlayUiState

    /**
     * The live game. [positions]/[sanMoves] are the same [org.rurbaniak.smartchessboard.domain.chess.pgn.ReplayGame]-shaped
     * parallel lists Replay uses (`positions.size == sanMoves.size + 1`); play always sits at the
     * live position [position] (the last one). [status] classifies that position — a [terminal]
     * one (checkmate/stalemate) blocks further input (the record stays in_progress — S-05 owns
     * result recording).
     */
    data class Playing(
        val positions: List<Position>,
        val sanMoves: List<String>,
        val selectedSquare: Int?,
        val targetSquares: Set<Int>,
        val pendingPromotion: PendingPromotion?,
        val status: GameStatus,
        /** The color rendered at the bottom of the board. */
        val orientation: Color,
        /** True while the journal holds moves the cloud has not confirmed (non-blocking indicator). */
        val syncPending: Boolean,
        val whiteLabel: String,
        val blackLabel: String,
    ) : PlayUiState {
        val position: Position get() = positions.last()

        val terminal: Boolean get() = status is GameStatus.Checkmate || status is GameStatus.Stalemate
    }
}

/**
 * MVVM (per `lessons.md` — the play state is a single coherent UiState driven by a handful of
 * intents; no MVI reducer ceremony, justified in the plan's Implementation Approach). Loads the
 * record, reconciles the local journal against the cloud (LWW per §3.4) via [GameAutoSaver], parses
 * the resolved PGN once into the replay-shaped parallel lists, then drives pass-and-play: tap to
 * select, tap a legal target to move, choose a promotion piece, flip the board.
 *
 * Move acceptance follows the §6.2 ordering invariant: a move is "accepted" only after the
 * synchronous journal write returns — `validate → SAN → serialize → journal write → UI update`,
 * then a best-effort cloud sync launched off the acceptance path.
 */
class PlayViewModel(
    private val gameId: String,
    private val gamesRepository: GamesRepository,
    private val autoSaver: GameAutoSaver,
    // Injected so tests pin the resume parse to a shared TestDispatcher; in production the default
    // keeps the one-shot parsePgn off the main thread (same pattern as ReplayViewModel).
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    /** Header tags for re-serialization after each move; derived from the record once at load. */
    private var meta: PgnMeta? = null

    init {
        // Reflect async sync transitions (a flush completing, or reconcile raising the indicator on
        // load) into the live state. acceptMove sets the value synchronously, so per-move state
        // copies read it directly; this collector covers the off-path transitions.
        viewModelScope.launch {
            autoSaver.syncPending.collect { pending ->
                _uiState.update { state ->
                    if (state is PlayUiState.Playing) state.copy(syncPending = pending) else state
                }
            }
        }
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        _uiState.value = PlayUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                try {
                    val record = gamesRepository.getGame(gameId)
                    meta = metaFor(record)
                    val pgn = autoSaver.reconcile(record)
                    withContext(parseDispatcher) {
                        val game = parsePgn(pgn)
                        PlayUiState.Playing(
                            positions = game.positions,
                            sanMoves = game.sanMoves,
                            selectedSquare = null,
                            targetSquares = emptySet(),
                            pendingPromotion = null,
                            status = status(game.positions.last()),
                            orientation = Color.WHITE,
                            syncPending = autoSaver.syncPending.value,
                            whiteLabel = record.whiteLabel,
                            blackLabel = record.blackLabel,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PlayUiState.Error
                }
        }
    }

    fun flipBoard() {
        _uiState.update { state ->
            if (state is PlayUiState.Playing) state.copy(orientation = state.orientation.opposite) else state
        }
    }

    /**
     * The single board-tap entry point. No-ops while a terminal position or a pending promotion
     * has the board frozen. Selects an own piece, deselects on re-tap, reselects on another own
     * piece, and moves (or opens the promotion picker) when a legal target is tapped.
     */
    fun onSquareTap(square: Int) {
        val state = _uiState.value as? PlayUiState.Playing ?: return
        if (state.terminal || state.pendingPromotion != null) return
        val live = state.position
        val selected = state.selectedSquare
        when {
            selected == null -> selectIfOwnPiece(state, live, square)

            square == selected -> _uiState.value = state.copy(selectedSquare = null, targetSquares = emptySet())

            square in state.targetSquares -> onTargetChosen(state, live, selected, square)

            // A tap that is neither the selection nor a legal target: reselect if it is another
            // own piece, otherwise clear the selection.
            else -> selectIfOwnPiece(state, live, square)
        }
    }

    fun onPromotionPick(pieceType: PieceType) {
        val state = _uiState.value as? PlayUiState.Playing ?: return
        val pending = state.pendingPromotion ?: return
        acceptMove(state, Move(from = pending.from, to = pending.to, promoteTo = pieceType))
    }

    fun onPromotionDismiss() {
        _uiState.update { state ->
            if (state is PlayUiState.Playing) {
                state.copy(pendingPromotion = null, selectedSquare = null, targetSquares = emptySet())
            } else {
                state
            }
        }
    }

    private fun selectIfOwnPiece(
        state: PlayUiState.Playing,
        live: Position,
        square: Int,
    ) {
        val piece = live.pieceAt(square)
        _uiState.value =
            if (piece != null && piece.color == live.sideToMove) {
                state.copy(
                    selectedSquare = square,
                    targetSquares = legalMoves(live).filter { it.from == square }.map { it.to }.toSet(),
                )
            } else {
                state.copy(selectedSquare = null, targetSquares = emptySet())
            }
    }

    private fun onTargetChosen(
        state: PlayUiState.Playing,
        live: Position,
        from: Int,
        to: Int,
    ) {
        // A promotion shows as one-or-more legal moves with the same from/to that carry a promoteTo;
        // defer acceptance to the picker so no move is saved until the piece is chosen (FR-006).
        val isPromotion = legalMoves(live).any { it.from == from && it.to == to && it.promoteTo != null }
        if (isPromotion) {
            _uiState.value =
                state.copy(
                    pendingPromotion = PendingPromotion(from = from, to = to, color = live.sideToMove),
                    selectedSquare = null,
                    targetSquares = emptySet(),
                )
        } else {
            acceptMove(state, Move(from = from, to = to))
        }
    }

    /**
     * The §6.2 acceptance sequence. validate/SAN/serialize are pure, local, and synchronous (well
     * under the 500 ms NFR), so they run inline; the durable journal write is the acceptance gate;
     * the UI updates only after it returns; the cloud sync is launched last, off the path.
     */
    private fun acceptMove(
        state: PlayUiState.Playing,
        move: Move,
    ) {
        val meta = meta ?: return
        val live = state.position
        val outcome = validate(live, move)
        if (outcome !is MoveOutcome.Legal) return // defensive: targets come from the legal set
        val nextPosition = outcome.position
        val sanMoves = state.sanMoves + sanForMove(live, move)
        // §6.2 gate: durable, synchronous journal write before the move counts as accepted.
        autoSaver.acceptMove(gameId, writePgn(meta, sanMoves))
        _uiState.value =
            state.copy(
                positions = state.positions + nextPosition,
                sanMoves = sanMoves,
                selectedSquare = null,
                targetSquares = emptySet(),
                pendingPromotion = null,
                status = status(nextPosition),
                syncPending = autoSaver.syncPending.value,
            )
        viewModelScope.launch { autoSaver.sync(gameId) }
    }

    /** Derives the PGN header tags from the record; [PgnMeta.date] is `YYYY.MM.DD` per §5.2. */
    private fun metaFor(record: GameRecord): PgnMeta =
        PgnMeta(
            event = "Smart Chessboard",
            date = record.createdAt.take(10).replace('-', '.'),
            white = record.whiteLabel,
            black = record.blackLabel,
            result = "*",
            mode = if (record.mode == GameMode.PHYSICAL) "physical" else "digital",
        )
}
