package org.rurbaniak.smartchessboard.presentation.physical

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.Resolution
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.board.resolvePhysicalMove
import org.rurbaniak.smartchessboard.domain.board.toOccupancy
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.games.gameResultFor
import org.rurbaniak.smartchessboard.presentation.play.EndGamePrompt
import org.rurbaniak.smartchessboard.presentation.play.PendingPromotion

/**
 * The pure heart of the physical-play screen: every `(state, message)` transition, no IO. All chess
 * reasoning it does — [resolvePhysicalMove], [status], [gameResultFor], [toOccupancy] — is pure, so
 * the reducer stays exhaustively testable on every target (the shape S-07/S-08 reuse). The only IO,
 * the §6.2 journal write, is deferred to the [PhysicalEffect.CommitMove] / [PhysicalEffect.FinishGame]
 * effects the ViewModel interprets: the state advances to an accepted move only on the [PhysicalMsg.MoveCommitted]
 * feedback, never inside [reduce], so a failed save can never show a move that was not durably saved.
 */
fun reduce(
    state: PhysicalPlayState,
    msg: PhysicalMsg,
): ReduceResult =
    when (state) {
        is PhysicalPlayState.Loading -> reduceLoading(msg)
        is PhysicalPlayState.Error -> reduceError(msg)
        is PhysicalPlayState.Playing -> reducePlaying(state, msg)
    }

private fun reduceLoading(msg: PhysicalMsg): ReduceResult =
    when (msg) {
        is PhysicalMsg.Loaded -> {
            // FR-013 resume gate: an in-progress physical game must confirm the physical board matches the
            // rebuilt expected position before move acceptance re-enables. A finished record (result != null,
            // e.g. a manual FR-018 end without a terminal status) is opened read-only and never gates — so
            // the predicate is pinned to `result == null && !terminal`, not terminal-status alone.
            val inProgress =
                msg.result == null &&
                    msg.status !is GameStatus.Checkmate &&
                    msg.status !is GameStatus.Stalemate
            val playing =
                PhysicalPlayState.Playing(
                    positions = msg.positions,
                    sanMoves = msg.sanMoves,
                    status = msg.status,
                    orientation = Color.WHITE,
                    syncPending = false,
                    whiteLabel = msg.whiteLabel,
                    blackLabel = msg.blackLabel,
                    connectionState =
                        if (msg.connected) BoardConnectionState.CONNECTED else BoardConnectionState.DISCONNECTED,
                    liftedSquares = emptySet(),
                    eventsSinceConfirm = emptyList(),
                    setupMismatch = false,
                    result = msg.result,
                    awaitingResumeConfirm = inProgress,
                )
            // The match never runs inline here — `reduceLoading` drops the on-connect snapshot/BoardConnected
            // that fired during Loading, so Playing always starts at latestOccupancy = null. When already
            // connected, re-request the snapshot whose dropped BoardConnected would have pulled; when
            // disconnected, the BoardConnected arm's request covers it once the board connects. Either way
            // the at-rest board-match runs only in the SnapshotReceived arm.
            val effects =
                if (inProgress && msg.connected) {
                    listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot))
                } else {
                    emptyList()
                }
            ReduceResult(playing, effects)
        }

        PhysicalMsg.LoadFailed -> {
            ReduceResult(PhysicalPlayState.Error)
        }

        // Connection / sync / board events that arrive before the game has loaded have nowhere to
        // land yet; the post-load snapshot burst re-establishes occupancy and connection state.
        else -> {
            ReduceResult(PhysicalPlayState.Loading)
        }
    }

private fun reduceError(msg: PhysicalMsg): ReduceResult =
    when (msg) {
        PhysicalMsg.Retry -> ReduceResult(PhysicalPlayState.Loading, listOf(PhysicalEffect.LoadGame))
        else -> ReduceResult(PhysicalPlayState.Error)
    }

private fun reducePlaying(
    state: PhysicalPlayState.Playing,
    msg: PhysicalMsg,
): ReduceResult =
    when (msg) {
        PhysicalMsg.BoardConnected -> {
            // Re-request occupancy on (re)connect: the hot, no-replay stream means a burst can be
            // missed, and a snapshot re-verifies the board against the live position. The board resets
            // to GAME mode on every reconnect (contract §1.7), so re-arm DIAGNOSTIC if the grid is open.
            // S-09/FR-012: arm the reconnect-reconcile gate here — acceptance holds from CONNECTED until
            // the post-reconnect snapshot confirms the board still matches (it may have changed out of
            // range). The gate clears through the shared SnapshotReceived board-match seam below.
            ReduceResult(
                state.copy(
                    connectionState = BoardConnectionState.CONNECTED,
                    reconnectReconciling = true,
                ),
                buildList {
                    add(PhysicalEffect.Send(BoardCommand.RequestSnapshot))
                    if (state.diagnosticsVisible) {
                        add(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)))
                    }
                },
            )
        }

        PhysicalMsg.BoardDisconnected -> {
            ReduceResult(state.copy(connectionState = BoardConnectionState.DISCONNECTED))
        }

        is PhysicalMsg.SnapshotReceived -> {
            // Keep the live occupancy fresh for the grid + restore-verify on every snapshot. The
            // setup-mismatch compare is only meaningful at rest (a snapshot mid-sequence is expected to
            // differ); during recovery the board is at rest, so an exact match clears the gate (FR-010).
            val atRest = state.eventsSinceConfirm.isEmpty()
            val matchesExpected = msg.occupancy == state.position.toOccupancy()
            val restoreVerified = state.recovering && matchesExpected
            // FR-013 resume gate clears through the SAME at-rest board-match check as reject-recovery —
            // the shared seam both resume (Loaded→RequestSnapshot) and reconnect (BoardConnected→Request-
            // Snapshot) funnel through. On a match the gate drops; the setupMismatch→false edge below drives
            // the lone SetMode(GAME) on the restore path and emits none on a clean match (the board never
            // left GAME). On a mismatch the gate holds, setupMismatch auto-opens the grid, and each later
            // snapshot re-runs this check until it matches. No explicit SetMode here — it would double-fire.
            val resumeVerified = state.awaitingResumeConfirm && matchesExpected
            // FR-012 reconnect-reconcile clears through the SAME at-rest board-match seam (parallel to the
            // resume gate): once a post-reconnect snapshot matches the live position, acceptance re-enables.
            val reconnectVerified = state.reconnectReconciling && matchesExpected
            val next =
                state.copy(
                    latestOccupancy = msg.occupancy,
                    setupMismatch = if (atRest) !matchesExpected else state.setupMismatch,
                    recovering = if (restoreVerified) false else state.recovering,
                    awaitingResumeConfirm = if (resumeVerified) false else state.awaitingResumeConfirm,
                    reconnectReconciling = if (reconnectVerified) false else state.reconnectReconciling,
                    rejection = if (restoreVerified) null else state.rejection,
                    manualDiagnostics = if (restoreVerified) false else state.manualDiagnostics,
                )
            // SetMode tracks the grid's visibility edge; a verified restore also re-pulls a snapshot.
            val effects =
                effectsForModeChange(state, next) +
                    if (restoreVerified) listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)) else emptyList()
            ReduceResult(next, effects)
        }

        is PhysicalMsg.SquareLifted -> {
            accumulate(state, BoardEvent.SquareEvent(msg.square, SquareEventType.LIFT))
        }

        is PhysicalMsg.SquarePlaced -> {
            accumulate(state, BoardEvent.SquareEvent(msg.square, SquareEventType.PLACE))
        }

        is PhysicalMsg.ConfirmPressed -> {
            confirm(state, msg.button)
        }

        is PhysicalMsg.PromotionPicked -> {
            if (state.pendingPromotion == null || state.result != null) {
                ReduceResult(state)
            } else {
                val pending = state.pendingPromotion
                ReduceResult(
                    state.copy(rejection = null),
                    listOf(
                        PhysicalEffect.CommitMove(
                            confirmed = state.position,
                            sanSoFar = state.sanMoves,
                            move = Move(from = pending.from, to = pending.to, promoteTo = msg.piece),
                        ),
                    ),
                )
            }
        }

        PhysicalMsg.PromotionDismissed -> {
            ReduceResult(
                state.copy(
                    pendingPromotion = null,
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                    rejection = null,
                ),
            )
        }

        PhysicalMsg.EndGameRequested -> {
            ReduceResult(if (state.result == null) state.copy(endGamePrompt = EndGamePrompt.Picking) else state)
        }

        is PhysicalMsg.ResultPicked -> {
            ReduceResult(
                if (state.result == null && state.endGamePrompt is EndGamePrompt.Picking) {
                    state.copy(endGamePrompt = EndGamePrompt.Confirming(msg.result))
                } else {
                    state
                },
            )
        }

        PhysicalMsg.EndGameConfirmed -> {
            val prompt = state.endGamePrompt
            if (state.result == null && prompt is EndGamePrompt.Confirming) {
                ReduceResult(
                    state.copy(result = prompt.result, endGamePrompt = null),
                    listOf(PhysicalEffect.FinishGame(prompt.result, state.sanMoves)),
                )
            } else {
                ReduceResult(state)
            }
        }

        PhysicalMsg.EndGameDismissed -> {
            ReduceResult(state.copy(endGamePrompt = null))
        }

        PhysicalMsg.FlipBoard -> {
            ReduceResult(state.copy(orientation = state.orientation.opposite))
        }

        PhysicalMsg.ShowDiagnostics -> {
            // Open the grid (FR-011). On the hidden→shown edge enter DIAGNOSTIC mode and pull a snapshot
            // so the grid populates at once; if already visible (a live setup-mismatch), this is a no-op.
            val next = state.copy(manualDiagnostics = true)
            val modeEffects = effectsForModeChange(state, next)
            ReduceResult(
                next,
                if (modeEffects.isEmpty()) {
                    modeEffects
                } else {
                    modeEffects +
                        PhysicalEffect.Send(BoardCommand.RequestSnapshot)
                },
            )
        }

        PhysicalMsg.HideDiagnostics -> {
            // Close the manually-opened grid; return to GAME mode only if nothing else still needs it
            // (a live setup-mismatch keeps the grid — and DIAGNOSTIC mode — on).
            val next = state.copy(manualDiagnostics = false)
            ReduceResult(next, effectsForModeChange(state, next))
        }

        is PhysicalMsg.MoveCommitted -> {
            commit(state, msg)
        }

        is PhysicalMsg.MoveRejected -> {
            ReduceResult(
                state.copy(
                    rejection = msg.reason,
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                    pendingPromotion = null,
                ),
            )
        }

        is PhysicalMsg.SyncChanged -> {
            ReduceResult(state.copy(syncPending = msg.pending))
        }

        // No-ops while playing: a second load result, or Retry (only meaningful from Error).
        is PhysicalMsg.Loaded, PhysicalMsg.LoadFailed, PhysicalMsg.Retry -> {
            ReduceResult(state)
        }
    }

/**
 * Append one lift/place event, recompute the lifted-square highlight, and raise/clear the promotion
 * picker from the running sequence. Ignored once the game is frozen (a finished board accepts no
 * input). The picker is detected on the *place* that lands a pawn on the last rank (decision 9).
 */
private fun accumulate(
    state: PhysicalPlayState.Playing,
    event: BoardEvent.SquareEvent,
): ReduceResult {
    // A frozen board accepts no input; during recovery, an unconfirmed resume, OR an unconfirmed reconnect
    // the lift/place are restoration moves, not a new move — the snapshot occupancy (not these deltas) drives
    // the at-rest board-match that clears the gate, so don't build a sequence (it would also leave stale events
    // that corrupt the next real move and, by making the board no longer at-rest, suppress the mismatch recompute).
    if (state.result != null || state.recovering || state.awaitingResumeConfirm || state.reconnectReconciling) {
        return ReduceResult(state)
    }
    val events = state.eventsSinceConfirm + event
    val pendingPromotion =
        when (val resolution = resolvePhysicalMove(state.position, events)) {
            is Resolution.NeedsPromotion -> {
                PendingPromotion(from = resolution.from, to = resolution.to, color = state.position.sideToMove)
            }

            else -> {
                null
            }
        }
    return ReduceResult(
        state.copy(
            eventsSinceConfirm = events,
            liftedSquares = liftedSquaresOf(events),
            pendingPromotion = pendingPromotion,
            rejection = null,
        ),
    )
}

/**
 * Resolve the running sequence into one move and request its durable save — or reject. A confirm on
 * the wrong side is a no-op (chess-clock semantics, decision); a confirm with a promotion still
 * unpicked is a reminder, not a save (contract §1.5); a frozen or paused board ignores confirms.
 */
private fun confirm(
    state: PhysicalPlayState.Playing,
    button: BoardButton,
): ReduceResult {
    if (state.result != null || state.paused) return ReduceResult(state)
    // The acceptance gate (acceptanceBlocked): a confirm can't advance the game while a reject awaits
    // restore ([recovering]), a resume awaits board confirmation ([awaitingResumeConfirm]), or a BLE
    // reconnect awaits its post-reconnect snapshot ([reconnectReconciling]) — but it *does* re-pull a
    // snapshot so the at-rest board-match can clear the gate without the grid.
    if (state.recovering || state.awaitingResumeConfirm || state.reconnectReconciling) {
        return ReduceResult(state, listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)))
    }
    if (button.toColor() != state.position.sideToMove) return ReduceResult(state)
    if (state.pendingPromotion != null) return ReduceResult(state.copy(rejection = RejectionReason.PROMOTION_REQUIRED))
    return when (val resolution = resolvePhysicalMove(state.position, state.eventsSinceConfirm)) {
        is Resolution.Resolved -> {
            ReduceResult(
                state.copy(rejection = null),
                listOf(PhysicalEffect.CommitMove(state.position, state.sanMoves, resolution.move)),
            )
        }

        is Resolution.NeedsPromotion -> {
            ReduceResult(
                state.copy(
                    pendingPromotion =
                        PendingPromotion(from = resolution.from, to = resolution.to, color = state.position.sideToMove),
                ),
            )
        }

        Resolution.Ambiguous -> {
            ReduceResult(
                state.copy(
                    rejection = RejectionReason.AMBIGUOUS,
                    recovering = true,
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                ),
            )
        }

        Resolution.Illegal -> {
            // Fork on the *absolute* board: if the freshest snapshot disagrees with the expected
            // position it's INCONSISTENT (FR-010); otherwise the delta sequence was simply ILLEGAL.
            // Degrades to ILLEGAL when no fresh snapshot is available (GAME-mode snapshots go stale).
            val fresh = state.latestOccupancy
            val inconsistent = fresh != null && fresh != state.position.toOccupancy()
            ReduceResult(
                state.copy(
                    rejection = if (inconsistent) RejectionReason.INCONSISTENT else RejectionReason.ILLEGAL,
                    recovering = true,
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                ),
                // Pull a fresh snapshot so the restore-verify (and any setup-mismatch auto-grid) have it.
                listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)),
            )
        }

        // Nothing resolvable yet (only j'adoube/noise, or a piece still in hand): keep waiting.
        Resolution.Incomplete -> {
            ReduceResult(state)
        }
    }
}

/**
 * The move was durably journaled ([PhysicalMsg.MoveCommitted] — the §6.2 gate cleared in the effect):
 * advance the game, reset the sequence, and auto-close on mate/stalemate (FR-007) via a separate
 * finish gate, exactly as the digital flow does.
 */
private fun commit(
    state: PhysicalPlayState.Playing,
    msg: PhysicalMsg.MoveCommitted,
): ReduceResult {
    val positions = state.positions + msg.nextPosition
    val sanMoves = state.sanMoves + msg.san
    val nextStatus = status(msg.nextPosition)
    val advanced =
        state.copy(
            positions = positions,
            sanMoves = sanMoves,
            status = nextStatus,
            eventsSinceConfirm = emptyList(),
            liftedSquares = emptySet(),
            pendingPromotion = null,
            rejection = null,
            setupMismatch = false,
            recovering = false,
            manualDiagnostics = false,
            awaitingResumeConfirm = false,
            reconnectReconciling = false,
            syncPending = true,
        )
    val autoResult = gameResultFor(nextStatus, msg.nextPosition.sideToMove)
    return if (autoResult != null) {
        ReduceResult(advanced.copy(result = autoResult), listOf(PhysicalEffect.FinishGame(autoResult, sanMoves)))
    } else {
        ReduceResult(advanced)
    }
}

/**
 * SetMode effect for a diagnostics-visibility transition: enter DIAGNOSTIC on the hidden→shown edge,
 * return to GAME on shown→hidden, nothing when visibility is unchanged. Centralising the edge on the
 * derived [PhysicalPlayState.Playing.diagnosticsVisible] lets every arm that flips a contributing flag
 * (show / hide, setup-mismatch, restore-clear) avoid spamming SetMode per snapshot (contract §1.6).
 */
private fun effectsForModeChange(
    prev: PhysicalPlayState.Playing,
    next: PhysicalPlayState.Playing,
): List<PhysicalEffect> =
    when {
        !prev.diagnosticsVisible && next.diagnosticsVisible -> {
            listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)))
        }

        prev.diagnosticsVisible && !next.diagnosticsVisible -> {
            listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME)))
        }

        else -> {
            emptyList()
        }
    }

/** Squares with a piece currently lifted: a LIFT adds, a PLACE back onto the same square removes. */
private fun liftedSquaresOf(events: List<BoardEvent.SquareEvent>): Set<Int> {
    val lifted = mutableSetOf<Int>()
    for (event in events) {
        when (event.type) {
            SquareEventType.LIFT -> lifted += event.square
            SquareEventType.PLACE -> lifted -= event.square
        }
    }
    return lifted
}

private fun BoardButton.toColor(): Color =
    when (this) {
        BoardButton.WHITE -> Color.WHITE
        BoardButton.BLACK -> Color.BLACK
    }
