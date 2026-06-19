package org.rurbaniak.smartchessboard.presentation.physical

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.Resolution
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.board.resolvePhysicalMove
import org.rurbaniak.smartchessboard.domain.board.toOccupancy
import org.rurbaniak.smartchessboard.domain.chess.Color
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
            ReduceResult(
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
                ),
            )
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
            // missed, and a snapshot re-verifies the board against the live position.
            ReduceResult(
                state.copy(connectionState = BoardConnectionState.CONNECTED),
                listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)),
            )
        }

        PhysicalMsg.BoardDisconnected -> {
            ReduceResult(state.copy(connectionState = BoardConnectionState.DISCONNECTED))
        }

        is PhysicalMsg.SnapshotReceived -> {
            // Only meaningful at rest (no move in hand): a snapshot taken mid-sequence is expected to
            // differ from the live position. Compares occupancy bitmaps — no FEN needed.
            ReduceResult(
                if (state.eventsSinceConfirm.isEmpty()) {
                    state.copy(setupMismatch = msg.occupancy != state.position.toOccupancy())
                } else {
                    state
                },
            )
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
    if (state.result != null) return ReduceResult(state)
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
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                ),
            )
        }

        Resolution.Illegal -> {
            ReduceResult(
                state.copy(
                    rejection = RejectionReason.ILLEGAL,
                    eventsSinceConfirm = emptyList(),
                    liftedSquares = emptySet(),
                ),
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
            syncPending = true,
        )
    val autoResult = gameResultFor(nextStatus, msg.nextPosition.sideToMove)
    return if (autoResult != null) {
        ReduceResult(advanced.copy(result = autoResult), listOf(PhysicalEffect.FinishGame(autoResult, sanMoves)))
    } else {
        ReduceResult(advanced)
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
