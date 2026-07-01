package org.rurbaniak.smartchessboard.presentation.physical

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rurbaniak.smartchessboard.domain.board.BoardConnection
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardTransport
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.chess.MoveOutcome
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.pgn.PgnMeta
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.pgn.sanForMove
import org.rurbaniak.smartchessboard.domain.chess.pgn.writePgn
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.chess.validate
import org.rurbaniak.smartchessboard.domain.games.GameAutoSaver
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.domain.games.GamesRepository
import org.rurbaniak.smartchessboard.domain.games.toPgnResultToken
import kotlin.coroutines.cancellation.CancellationException
import org.rurbaniak.smartchessboard.domain.games.GameStatus as RecordStatus

/**
 * The impure shell around the pure [reduce]. It owns the three responsibilities the reducer cannot:
 * (1) it collects `boardConnection.events` / `connectionState` **before** the board connects — the
 * stream is hot and replay-free, so a late subscriber misses the on-connect snapshot burst; (2) it
 * interprets [PhysicalEffect]s against the reused S-04 back half ([GamesRepository] / [GameAutoSaver]),
 * running the §6.2 gate (the synchronous journal write) inside [PhysicalEffect.CommitMove] and feeding
 * back [PhysicalMsg.MoveCommitted] / [PhysicalMsg.MoveRejected]; (3) it exposes [state] as a [StateFlow].
 *
 * Connection lifecycle is deliberately not the ViewModel's: [BoardConnection] models only the
 * connected-consumer view (no connect/disconnect — that is the adapter's, per its port doc). The
 * emulator is connected by the platform layer / the test driver after this ViewModel has subscribed.
 */
class PhysicalPlayViewModel(
    private val gameId: String,
    private val gamesRepository: GamesRepository,
    private val autoSaver: GameAutoSaver,
    private val boardConnection: BoardConnection,
    // The transport face of the same adapter (S-09): used only to re-drive a dropped link from the
    // play screen's "Reconnect" affordance. Null in unit/E2E tests (they drive an EmulatedBoard, which
    // is the port only); production DI passes the Kable adapter, which is both faces of one instance.
    private val boardTransport: BoardTransport? = null,
    // Injected so tests pin the resume parse to a shared TestDispatcher; in production the default
    // keeps the one-shot parsePgn off the main thread (same pattern as the digital PlayViewModel).
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<PhysicalPlayState>(PhysicalPlayState.Loading)
    val state: StateFlow<PhysicalPlayState> = _state.asStateFlow()

    /** Header tags for re-serialization after each move; derived from the record once at load. */
    private var meta: PgnMeta? = null

    init {
        // Subscribe to every inbound stream first — before the board is driven to CONNECTED — so the
        // on-connect snapshot+status burst (§1.3) is never missed.
        viewModelScope.launch { autoSaver.syncPending.collect { dispatch(PhysicalMsg.SyncChanged(it)) } }
        viewModelScope.launch {
            boardConnection.connectionState.collect { connectionState ->
                dispatch(
                    if (connectionState == BoardConnectionState.CONNECTED) {
                        PhysicalMsg.BoardConnected
                    } else {
                        PhysicalMsg.BoardDisconnected
                    },
                )
            }
        }
        viewModelScope.launch {
            boardConnection.events.collect { event -> event.toMsg()?.let(::dispatch) }
        }
        load()
    }

    // User-origin intents the screen sends. Board-origin messages (lift / place / button / snapshot /
    // connection) are never sent from the UI — they arrive only through the collected board streams.

    fun flipBoard() = dispatch(PhysicalMsg.FlipBoard)

    /** Open the live reed diagnostics grid (FR-011); the reducer arms DIAGNOSTIC mode and pulls a snapshot. */
    fun showDiagnostics() = dispatch(PhysicalMsg.ShowDiagnostics)

    /** Dismiss the diagnostics grid; the reducer returns the board to GAME mode if nothing else still needs it. */
    fun hideDiagnostics() = dispatch(PhysicalMsg.HideDiagnostics)

    fun pickPromotion(piece: PieceType) = dispatch(PhysicalMsg.PromotionPicked(piece))

    fun dismissPromotion() = dispatch(PhysicalMsg.PromotionDismissed)

    fun requestEndGame() = dispatch(PhysicalMsg.EndGameRequested)

    fun pickResult(result: GameResult) = dispatch(PhysicalMsg.ResultPicked(result))

    fun confirmEndGame() = dispatch(PhysicalMsg.EndGameConfirmed)

    fun dismissEndGame() = dispatch(PhysicalMsg.EndGameDismissed)

    fun retry() = dispatch(PhysicalMsg.Retry)

    /**
     * Re-drive a dropped BLE link from the disconnected banner (S-09 Phase 8). Delegates to the
     * transport (the adapter's other face) — the reconnect lives below the port, so success arrives back
     * through the collected `connectionState` as the usual CONNECTED → reconnect-reconcile snapshot. The
     * adapter also auto-retries on a drop; this is the manual escape hatch when that backed off.
     */
    fun reconnect() {
        val transport = boardTransport ?: return
        viewModelScope.launch {
            try {
                transport.reconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort: a failed attempt leaves the banner up; the user can press Reconnect again.
            }
        }
    }

    /** The single funnel: reduce, publish the next state, then run whatever effects it requested. */
    private fun dispatch(msg: PhysicalMsg) {
        val (next, effects) = reduce(_state.value, msg)
        _state.value = next
        effects.forEach(::runEffect)
    }

    private fun runEffect(effect: PhysicalEffect) {
        when (effect) {
            PhysicalEffect.LoadGame -> load()
            is PhysicalEffect.CommitMove -> commitMove(effect)
            is PhysicalEffect.FinishGame -> finishGame(effect)
            is PhysicalEffect.Send -> send(effect)
        }
    }

    private fun load() {
        _state.value = PhysicalPlayState.Loading
        viewModelScope.launch {
            try {
                val record = gamesRepository.getGame(gameId)
                meta = metaFor(record)
                val pgn = autoSaver.reconcile(record)
                val game = withContext(parseDispatcher) { parsePgn(pgn) }
                dispatch(
                    PhysicalMsg.Loaded(
                        positions = game.positions,
                        sanMoves = game.sanMoves,
                        whiteLabel = record.whiteLabel,
                        blackLabel = record.blackLabel,
                        status = status(game.positions.last()),
                        // Defensive: routing sends finished → Replay, but render a frozen result if opened here.
                        result = if (record.status == RecordStatus.FINISHED) record.result else null,
                        connected = boardConnection.connectionState.value == BoardConnectionState.CONNECTED,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Throwable (not Exception): a wasm Ktor fetch failure is a kotlin.Error.
                dispatch(PhysicalMsg.LoadFailed)
            }
        }
    }

    /**
     * The §6.2 acceptance gate. validate / SAN / serialize are pure and synchronous; the durable
     * journal write ([GameAutoSaver.acceptMove]) is the gate; only its success feeds back
     * [PhysicalMsg.MoveCommitted] (which advances the state) — a throw feeds back
     * [PhysicalMsg.MoveRejected], so the state never shows a move that was not durably saved. Run in a
     * launch so the feedback dispatch lands on a later turn, never re-entering the current reduce.
     */
    private fun commitMove(effect: PhysicalEffect.CommitMove) {
        val meta = meta ?: return
        viewModelScope.launch {
            try {
                val outcome = validate(effect.confirmed, effect.move)
                if (outcome !is MoveOutcome.Legal) {
                    dispatch(PhysicalMsg.MoveRejected(RejectionReason.ILLEGAL))
                    return@launch
                }
                val san = sanForMove(effect.confirmed, effect.move)
                autoSaver.acceptMove(gameId, writePgn(meta, effect.sanSoFar + san))
                dispatch(PhysicalMsg.MoveCommitted(outcome.position, san))
                viewModelScope.launch { autoSaver.sync(gameId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                dispatch(PhysicalMsg.MoveRejected(RejectionReason.SAVE_FAILED))
            }
        }
    }

    /**
     * The offline-safe close (auto mate/stalemate or manual end). The reducer has already frozen the
     * UI with the result; this lands the durable finished-PGN write and launches the best-effort
     * cloud flush (which keeps retrying for a terminal entry — `lessons.md`).
     */
    private fun finishGame(effect: PhysicalEffect.FinishGame) {
        val meta = meta ?: return
        val pgn = writePgn(meta.copy(result = effect.result.toPgnResultToken()), effect.sanMoves)
        autoSaver.finishGame(gameId, effect.result, pgn)
        viewModelScope.launch { autoSaver.sync(gameId) }
    }

    private fun send(effect: PhysicalEffect.Send) {
        viewModelScope.launch {
            try {
                boardConnection.send(effect.command)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The board dropped between the reduce and the send; the next (re)connect re-requests.
            }
        }
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

/** Map a board → mobile event to a [PhysicalMsg]; DEVICE_STATUS carries no play-relevant state (ignored). */
private fun BoardEvent.toMsg(): PhysicalMsg? =
    when (this) {
        is BoardEvent.SquareEvent -> {
            when (type) {
                SquareEventType.LIFT -> PhysicalMsg.SquareLifted(square)
                SquareEventType.PLACE -> PhysicalMsg.SquarePlaced(square)
            }
        }

        is BoardEvent.ButtonEvent -> {
            PhysicalMsg.ConfirmPressed(button)
        }

        is BoardEvent.BoardSnapshot -> {
            PhysicalMsg.SnapshotReceived(occupancy)
        }

        is BoardEvent.DeviceStatus -> {
            null
        }
    }
