package org.rurbaniak.smartchessboard.presentation.physical

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.presentation.play.EndGamePrompt
import org.rurbaniak.smartchessboard.presentation.play.PendingPromotion

/**
 * MVI contract for the physical-play screen (S-06). Unlike the digital [org.rurbaniak.smartchessboard.presentation.play.PlayViewModel]
 * (MVVM), this screen merges an inbound board-event stream (lift / place / button / snapshot /
 * connection state) with a sequence-accumulation state machine, so every transition is funnelled
 * into one [PhysicalMsg] and resolved by one pure [reduce]. The reducer performs **no IO** — the
 * §6.2 acceptance gate (the synchronous journal write) lives in the [PhysicalEffect.CommitMove]
 * effect the ViewModel interprets, so a failed save can never leave the state showing an accepted
 * move. MVI is justified in `plan.md` (and permitted by `lessons.md` for genuinely event-heavy
 * boards); the digital screen stays MVVM.
 */
sealed interface PhysicalPlayState {
    data object Loading : PhysicalPlayState

    /** Retryable — game load / reconcile failed (same convention as the digital Play/Replay/History). */
    data object Error : PhysicalPlayState

    /**
     * The live physical game. [positions]/[sanMoves] are the same replay-shaped parallel lists the
     * digital flow uses (`positions.size == sanMoves.size + 1`); play sits at the live [position].
     * [meta] travels in state (not a ViewModel field) so the reducer can stay the single source of
     * the move it asks the effect to persist.
     *
     * Physical-specific surface: [connectionState] (acceptance is [paused] while disconnected),
     * [liftedSquares] (highlight, decision 8), [eventsSinceConfirm] (the lift/place sequence the
     * interpreter resolves on confirm), [setupMismatch] (the opening-position occupancy check), a
     * transient [rejection] reason, and the S-07 reject-recover surface ([latestOccupancy],
     * [recovering], [manualDiagnostics]) that pauses acceptance until the board is physically restored.
     */
    data class Playing(
        val positions: List<Position>,
        val sanMoves: List<String>,
        val status: GameStatus,
        /** The color rendered at the bottom of the board. */
        val orientation: Color,
        /** True while the journal holds moves the cloud has not confirmed (non-blocking indicator). */
        val syncPending: Boolean,
        val whiteLabel: String,
        val blackLabel: String,
        val connectionState: BoardConnectionState,
        /** Squares with a piece currently lifted (picked up, not yet placed back) — for the tint overlay. */
        val liftedSquares: Set<Int>,
        /** The lift/place events recorded since the last confirmation; resolved into one move on confirm. */
        val eventsSinceConfirm: List<BoardEvent.SquareEvent>,
        /** True when the latest snapshot's occupancy does not match the expected [position] (set up the board). */
        val setupMismatch: Boolean,
        /** Non-null once the game is closed (auto mate/stalemate or manual end) ⇒ frozen + final banner. */
        val result: GameResult? = null,
        /** The in-flight manual end-game flow; null while no picker/confirmation is showing. */
        val endGamePrompt: EndGamePrompt? = null,
        /** A pawn move awaiting the player's promotion choice — confirmation is blocked until it resolves. */
        val pendingPromotion: PendingPromotion? = null,
        /** The last rejected confirmation, surfaced transiently; cleared on the next accepted move. */
        val rejection: RejectionReason? = null,
        /**
         * Occupancy of the most recent snapshot (a1 = bit 0, h8 = sign bit), kept fresh for the live
         * diagnostics grid and the restore-verification; null until the first snapshot arrives. Test
         * bits with `(bits and (1L shl n)) != 0L` — never a signed `> 0` (h8 is the sign bit).
         */
        val latestOccupancy: Long? = null,
        /**
         * The reject-recovery acceptance gate (S-07, FR-010): a confirmation that resolves to an
         * illegal / ambiguous / inconsistent sequence pauses the game here, and it clears *only* when a
         * snapshot's occupancy equals `position.toOccupancy()` — the previous legal position restored.
         */
        val recovering: Boolean = false,
        /** True while the player opened the diagnostics grid via the banner CTA (vs auto-entry on [setupMismatch]). */
        val manualDiagnostics: Boolean = false,
        /**
         * The resume-confirmation gate (S-08, FR-013): set when an in-progress physical game is
         * (re)loaded — e.g. after an app restart — so move acceptance stays blocked until a snapshot's
         * occupancy equals `position.toOccupancy()` confirms the physical board matches the rebuilt
         * expected position. Cleared by the same at-rest board-match check that clears [recovering] — the
         * shared `SnapshotReceived` seam FR-012/S-09 reuses on BLE reconnect. Kept distinct from
         * [recovering] so the UI can tell "confirming the board on resume" from "your move was rejected".
         */
        val awaitingResumeConfirm: Boolean = false,
        /**
         * The reconnect-reconcile gate (S-09, FR-012): set in the [PhysicalMsg.BoardConnected] arm on every
         * BLE (re)connect — acceptance stays blocked from CONNECTED until a snapshot's occupancy equals
         * `position.toOccupancy()` confirms the physical board still matches the live position (it may have
         * changed while out of range). Cleared by the SAME at-rest board-match check that clears [recovering]
         * and [awaitingResumeConfirm] — the shared `SnapshotReceived` seam. Kept distinct from
         * [awaitingResumeConfirm] so the UI can tell "reconnecting" from "resuming" from "your move was rejected".
         */
        val reconnectReconciling: Boolean = false,
    ) : PhysicalPlayState {
        val position: Position get() = positions.last()

        val terminal: Boolean get() = status is GameStatus.Checkmate || status is GameStatus.Stalemate

        /** Move acceptance pauses while the board is unreachable (no reconcile until S-07; no save while down). */
        val paused: Boolean get() = connectionState == BoardConnectionState.DISCONNECTED

        /**
         * Acceptance is blocked while the board is down ([paused]), a reject awaits restore ([recovering]),
         * a resume awaits board confirmation ([awaitingResumeConfirm]), *or* a BLE reconnect awaits its
         * post-reconnect snapshot ([reconnectReconciling]).
         */
        val acceptanceBlocked: Boolean get() = paused || recovering || awaitingResumeConfirm || reconnectReconciling

        /** The diagnostics grid is on screen when opened manually *or* auto-shown by a [setupMismatch]. */
        val diagnosticsVisible: Boolean get() = manualDiagnostics || setupMismatch
    }
}

/** Why a confirmation produced no accepted move — surfaced as the transient [PhysicalPlayState.Playing.rejection]. */
enum class RejectionReason {
    /** The resolved sequence is not a legal move (or matched no move at all). */
    ILLEGAL,

    /**
     * The physical board's absolute occupancy does not reconcile with the expected position (S-07,
     * FR-010) — distinct from [ILLEGAL]: the *delta* sequence may have completed, but the board as a
     * whole disagrees with `positions.last()`. A reducer-level occupancy compare decides it; the
     * interpreter stays delta-only.
     */
    INCONSISTENT,

    /** More than one legal move matched (defensive — unreachable under a full lift/place stream). */
    AMBIGUOUS,

    /** Confirm was pressed while a promotion piece still has to be picked (contract §1.5). */
    PROMOTION_REQUIRED,

    /** The durable journal write failed; the move was NOT accepted and the state did not advance. */
    SAVE_FAILED,
}

/** Everything that can drive the physical-play state — board-origin, user-origin, and effect feedback. */
sealed interface PhysicalMsg {
    // --- board-origin (mapped from BoardConnection.events / connectionState) ---
    data object BoardConnected : PhysicalMsg

    data object BoardDisconnected : PhysicalMsg

    data class SnapshotReceived(
        val occupancy: Long,
    ) : PhysicalMsg

    data class SquareLifted(
        val square: Int,
    ) : PhysicalMsg

    data class SquarePlaced(
        val square: Int,
    ) : PhysicalMsg

    data class ConfirmPressed(
        val button: BoardButton,
    ) : PhysicalMsg

    // --- user-origin ---
    data class PromotionPicked(
        val piece: PieceType,
    ) : PhysicalMsg

    data object PromotionDismissed : PhysicalMsg

    data object EndGameRequested : PhysicalMsg

    data class ResultPicked(
        val result: GameResult,
    ) : PhysicalMsg

    data object EndGameConfirmed : PhysicalMsg

    data object EndGameDismissed : PhysicalMsg

    data object FlipBoard : PhysicalMsg

    /** User opened the live reed diagnostics grid via the recovery banner (S-07, FR-011). */
    data object ShowDiagnostics : PhysicalMsg

    /** User dismissed the diagnostics grid; the board returns to GAME mode if nothing else needs it. */
    data object HideDiagnostics : PhysicalMsg

    data object Retry : PhysicalMsg

    // --- effect feedback ---
    data class Loaded(
        val positions: List<Position>,
        val sanMoves: List<String>,
        val whiteLabel: String,
        val blackLabel: String,
        val status: GameStatus,
        val result: GameResult?,
        val connected: Boolean,
    ) : PhysicalMsg

    data object LoadFailed : PhysicalMsg

    /** The §6.2 journal write succeeded for [san] (the move from the resolved sequence) — advance the game. */
    data class MoveCommitted(
        val nextPosition: Position,
        val san: String,
    ) : PhysicalMsg

    data class MoveRejected(
        val reason: RejectionReason,
    ) : PhysicalMsg

    /** A new cloud-sync pending value from [org.rurbaniak.smartchessboard.domain.games.GameAutoSaver.syncPending]. */
    data class SyncChanged(
        val pending: Boolean,
    ) : PhysicalMsg
}

/** The IO the reducer may request; the ViewModel is the only interpreter (keeps [reduce] pure). */
sealed interface PhysicalEffect {
    /** Load + reconcile the game and parse its PGN. The ViewModel supplies the gameId (a VM constant). */
    data object LoadGame : PhysicalEffect

    /** Run validate → SAN → writePgn → GameAutoSaver.acceptMove (the §6.2 gate), then feed back MoveCommitted/MoveRejected. */
    data class CommitMove(
        val confirmed: Position,
        val sanSoFar: List<String>,
        val move: Move,
    ) : PhysicalEffect

    /** Write the finished PGN ([sanSoFar] + the result token) and close the game via GameAutoSaver.finishGame. */
    data class FinishGame(
        val result: GameResult,
        val sanMoves: List<String>,
    ) : PhysicalEffect

    /** Send a command to the board (e.g. RequestSnapshot on (re)connect). No-op feedback. */
    data class Send(
        val command: BoardCommand,
    ) : PhysicalEffect
}

/** The result of one [reduce] step: the next state plus any effects the ViewModel must run. */
data class ReduceResult(
    val state: PhysicalPlayState,
    val effects: List<PhysicalEffect> = emptyList(),
)
