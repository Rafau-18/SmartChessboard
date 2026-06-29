package org.rurbaniak.smartchessboard.presentation.physical

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.BoardMode
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fen
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.chess.status
import org.rurbaniak.smartchessboard.domain.games.GameResult
import org.rurbaniak.smartchessboard.presentation.play.EndGamePrompt
import org.rurbaniak.smartchessboard.presentation.play.PendingPromotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pure (state, msg) transitions of the physical-play reducer. No coroutines, no IO — reduce is a
// pure function, so each case is a direct call + assertion (the IO-freedom the plan requires, 3.5).
// Event lists are built raw with the same Square.kt convention the BoardScenarios DSL uses.
class PhysicalPlayReducerTest {
    private fun sq(name: String): Int = squareOf(name[0] - 'a', name[1] - '1')

    private fun lift(name: String) = BoardEvent.SquareEvent(sq(name), SquareEventType.LIFT)

    private fun place(name: String) = BoardEvent.SquareEvent(sq(name), SquareEventType.PLACE)

    private fun positionAfter(pgn: String): Position = parsePgn(pgn).positions.last()

    private fun playing(
        positions: List<Position> = listOf(Position.start()),
        sanMoves: List<String> = emptyList(),
        status: GameStatus = GameStatus.Ongoing,
        connectionState: BoardConnectionState = BoardConnectionState.CONNECTED,
        eventsSinceConfirm: List<BoardEvent.SquareEvent> = emptyList(),
        liftedSquares: Set<Int> = emptySet(),
        pendingPromotion: PendingPromotion? = null,
        result: GameResult? = null,
        endGamePrompt: EndGamePrompt? = null,
        rejection: RejectionReason? = null,
        setupMismatch: Boolean = false,
        latestOccupancy: Long? = null,
        recovering: Boolean = false,
        manualDiagnostics: Boolean = false,
        awaitingResumeConfirm: Boolean = false,
        reconnectReconciling: Boolean = false,
    ) = PhysicalPlayState.Playing(
        positions = positions,
        sanMoves = sanMoves,
        status = status,
        orientation = Color.WHITE,
        syncPending = false,
        whiteLabel = "White",
        blackLabel = "Black",
        connectionState = connectionState,
        liftedSquares = liftedSquares,
        eventsSinceConfirm = eventsSinceConfirm,
        setupMismatch = setupMismatch,
        result = result,
        endGamePrompt = endGamePrompt,
        pendingPromotion = pendingPromotion,
        rejection = rejection,
        latestOccupancy = latestOccupancy,
        recovering = recovering,
        manualDiagnostics = manualDiagnostics,
        awaitingResumeConfirm = awaitingResumeConfirm,
        reconnectReconciling = reconnectReconciling,
    )

    private fun playingAfter(reduceResult: ReduceResult): PhysicalPlayState.Playing =
        assertIs<PhysicalPlayState.Playing>(reduceResult.state)

    // --- Load lifecycle ---

    @Test
    fun loadedBuildsPlayingFromTheRecord() {
        val result =
            reduce(
                PhysicalPlayState.Loading,
                PhysicalMsg.Loaded(
                    positions = listOf(Position.start()),
                    sanMoves = emptyList(),
                    whiteLabel = "Alice",
                    blackLabel = "Bob",
                    status = GameStatus.Ongoing,
                    result = null,
                    connected = true,
                ),
            )
        val playing = playingAfter(result)
        assertEquals("Alice", playing.whiteLabel)
        assertEquals(BoardConnectionState.CONNECTED, playing.connectionState)
        assertEquals(Color.WHITE, playing.orientation)
        // FR-013: an in-progress physical resume gates acceptance and (when connected) re-pulls the
        // on-connect snapshot dropped during Loading so the board-match check can clear the gate.
        assertTrue(playing.awaitingResumeConfirm, "an in-progress physical resume gates acceptance")
        assertTrue(playing.acceptanceBlocked)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
    }

    @Test
    fun loadFailedGoesToError() {
        assertEquals(PhysicalPlayState.Error, reduce(PhysicalPlayState.Loading, PhysicalMsg.LoadFailed).state)
    }

    @Test
    fun retryFromErrorReloads() {
        val result = reduce(PhysicalPlayState.Error, PhysicalMsg.Retry)
        assertEquals(PhysicalPlayState.Loading, result.state)
        assertEquals(listOf(PhysicalEffect.LoadGame), result.effects)
    }

    // --- Confirmation: the §6.2 gate is requested, not performed, by the reducer ---

    @Test
    fun resolvedConfirmRequestsCommitWithoutAdvancingTheState() {
        val state = playing(eventsSinceConfirm = listOf(lift("e2"), place("e4")))
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        // State is unchanged (no optimistic advance — the move counts only after MoveCommitted).
        assertEquals(1, playingAfter(result).positions.size)
        assertEquals(
            listOf(PhysicalEffect.CommitMove(state.position, emptyList(), Move(sq("e2"), sq("e4")))),
            result.effects,
        )
    }

    @Test
    fun wrongSideButtonIsANoOp() {
        val state = playing(eventsSinceConfirm = listOf(lift("e2"), place("e4")))
        // White to move, but the Black button was pressed: ignore and keep waiting (chess-clock semantics).
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.BLACK))
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun confirmWhileDisconnectedIsANoOp() {
        val state =
            playing(
                connectionState = BoardConnectionState.DISCONNECTED,
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun confirmBeforePromotionPickIsAReminderNotASave() {
        val state =
            playing(
                positions = listOf(fen("4k3/P7/8/8/8/8/8/4K3 w - -")),
                pendingPromotion = PendingPromotion(from = sq("a7"), to = sq("a8"), color = Color.WHITE),
                eventsSinceConfirm = listOf(lift("a7"), place("a8")),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertEquals(RejectionReason.PROMOTION_REQUIRED, playingAfter(result).rejection)
        assertTrue(result.effects.isEmpty(), "a confirm before picking saves nothing")
    }

    @Test
    fun illegalSequenceConfirmRejectsPausesAndRequestsASnapshot() {
        // e2 to e5 is not a legal pawn move; the resolver returns Illegal. With no fresh snapshot
        // contradicting the expected position the reason stays ILLEGAL — but the game now *pauses*
        // (recovering) and pulls a snapshot to drive restore-verification (S-07, FR-010).
        val state = playing(eventsSinceConfirm = listOf(lift("e2"), place("e5")))
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        val playing = playingAfter(result)
        assertEquals(RejectionReason.ILLEGAL, playing.rejection)
        assertTrue(playing.recovering)
        assertTrue(playing.acceptanceBlocked)
        assertTrue(playing.eventsSinceConfirm.isEmpty())
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
    }

    // --- Promotion detection on the place ---

    @Test
    fun promotionLandingOnLastRankRaisesThePicker() {
        val state = playing(positions = listOf(fen("4k3/P7/8/8/8/8/8/4K3 w - -")))
        val lifted = reduce(state, PhysicalMsg.SquareLifted(sq("a7")))
        assertNull(playingAfter(lifted).pendingPromotion, "still in hand — not a promotion yet")
        val placed = reduce(playingAfter(lifted), PhysicalMsg.SquarePlaced(sq("a8")))
        val pending = playingAfter(placed).pendingPromotion
        assertEquals(sq("a7"), pending?.from)
        assertEquals(sq("a8"), pending?.to)
    }

    @Test
    fun promotionPickedRequestsCommitWithTheChosenPiece() {
        val state =
            playing(
                positions = listOf(fen("4k3/P7/8/8/8/8/8/4K3 w - -")),
                pendingPromotion = PendingPromotion(from = sq("a7"), to = sq("a8"), color = Color.WHITE),
                eventsSinceConfirm = listOf(lift("a7"), place("a8")),
            )
        val result = reduce(state, PhysicalMsg.PromotionPicked(PieceType.QUEEN))
        assertEquals(
            listOf(
                PhysicalEffect.CommitMove(
                    state.position,
                    emptyList(),
                    Move(sq("a7"), sq("a8"), promoteTo = PieceType.QUEEN),
                ),
            ),
            result.effects,
        )
    }

    // --- MoveCommitted advances; a mating move auto-closes ---

    @Test
    fun moveCommittedAdvancesTheGameAndClearsTheSequence() {
        val state =
            playing(
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
                liftedSquares = setOf(sq("e2")),
            )
        val result = reduce(state, PhysicalMsg.MoveCommitted(positionAfter("1. e4"), "e4"))
        val playing = playingAfter(result)
        assertEquals(2, playing.positions.size)
        assertEquals(listOf("e4"), playing.sanMoves)
        assertTrue(playing.eventsSinceConfirm.isEmpty())
        assertTrue(playing.liftedSquares.isEmpty())
        assertNull(playing.result)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun matingMoveAutoClosesViaFinishGame() {
        // Fool's mate: 1. f3 e5 2. g4 Qh4# — White is checkmated, so Black is recorded the winner.
        val state = playing(positions = listOf(positionAfter("1. f3 e5 2. g4")), sanMoves = listOf("f3", "e5", "g4"))
        val result = reduce(state, PhysicalMsg.MoveCommitted(positionAfter("1. f3 e5 2. g4 Qh4#"), "Qh4#"))
        val playing = playingAfter(result)
        assertEquals(GameResult.BLACK, playing.result)
        assertIs<GameStatus.Checkmate>(playing.status)
        assertEquals(
            listOf(PhysicalEffect.FinishGame(GameResult.BLACK, listOf("f3", "e5", "g4", "Qh4#"))),
            result.effects,
        )
    }

    @Test
    fun moveRejectedSurfacesTheReasonAndClearsTheSequenceWithoutAdvancing() {
        val state = playing(eventsSinceConfirm = listOf(lift("e2"), place("e4")))
        val result = reduce(state, PhysicalMsg.MoveRejected(RejectionReason.SAVE_FAILED))
        val playing = playingAfter(result)
        assertEquals(RejectionReason.SAVE_FAILED, playing.rejection)
        assertEquals(1, playing.positions.size, "a rejected save never advances the game")
        assertTrue(playing.eventsSinceConfirm.isEmpty())
    }

    // --- Connection + setup verification ---

    @Test
    fun disconnectPausesAndReconnectResumesAndReSyncs() {
        val disconnected = reduce(playing(), PhysicalMsg.BoardDisconnected)
        assertTrue(playingAfter(disconnected).paused)
        val reconnected = reduce(playingAfter(disconnected), PhysicalMsg.BoardConnected)
        assertTrue(!playingAfter(reconnected).paused)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), reconnected.effects)
    }

    @Test
    fun snapshotMismatchFlagsSetupAndAMatchClearsIt() {
        val mismatch = reduce(playing(), PhysicalMsg.SnapshotReceived(occupancy = 0L))
        assertTrue(playingAfter(mismatch).setupMismatch)
        val match = reduce(playingAfter(mismatch), PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        assertTrue(!playingAfter(match).setupMismatch)
    }

    private fun startOccupancy(): Long {
        // Ranks 1, 2, 7, 8 occupied — the start layout as a bitmap (matches Position.start()).
        var bits = 0L
        for (square in 0..63) if (Position.start().pieceAt(square) != null) bits = bits or (1L shl square)
        return bits
    }

    // --- Manual end-game flow mirrors the digital pick → confirm → finish ---

    @Test
    fun manualEndPickConfirmFinishesTheGame() {
        val picking = reduce(playing(sanMoves = listOf("e4")), PhysicalMsg.EndGameRequested)
        assertEquals(EndGamePrompt.Picking, playingAfter(picking).endGamePrompt)
        val confirming = reduce(playingAfter(picking), PhysicalMsg.ResultPicked(GameResult.DRAW))
        assertEquals(EndGamePrompt.Confirming(GameResult.DRAW), playingAfter(confirming).endGamePrompt)
        val finished = reduce(playingAfter(confirming), PhysicalMsg.EndGameConfirmed)
        assertEquals(GameResult.DRAW, playingAfter(finished).result)
        assertEquals(listOf(PhysicalEffect.FinishGame(GameResult.DRAW, listOf("e4"))), finished.effects)
    }

    @Test
    fun flipBoardTogglesOrientation() {
        assertEquals(Color.BLACK, playingAfter(reduce(playing(), PhysicalMsg.FlipBoard)).orientation)
    }

    @Test
    fun liftedSquaresTrackPickedUpPieces() {
        val afterLift = reduce(playing(), PhysicalMsg.SquareLifted(sq("e2")))
        assertEquals(setOf(sq("e2")), playingAfter(afterLift).liftedSquares)
        val afterPlace = reduce(playingAfter(afterLift), PhysicalMsg.SquarePlaced(sq("e4")))
        // The origin stays highlighted (piece moved away); the destination is not a "lifted" square.
        assertEquals(setOf(sq("e2")), playingAfter(afterPlace).liftedSquares)
    }

    // --- S-07: reject-recovery gate, INCONSISTENT fork, live diagnostics mode ---

    @Test
    fun illegalConfirmWithAFreshMismatchingSnapshotForksToInconsistent() {
        // The delta sequence is illegal AND a fresh snapshot disagrees with the expected position →
        // the reject is categorised INCONSISTENT (FR-010), not plain ILLEGAL.
        val state =
            playing(
                eventsSinceConfirm = listOf(lift("e2"), place("e5")),
                latestOccupancy = 0L, // empty board ≠ the start position
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        val playing = playingAfter(result)
        assertEquals(RejectionReason.INCONSISTENT, playing.rejection)
        assertTrue(playing.recovering)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
    }

    @Test
    fun illegalConfirmWithAMatchingSnapshotStaysIllegal() {
        // The board absolutely matches the expected position (stale-but-correct), so an illegal delta
        // sequence is plain ILLEGAL — the absolute fork only fires on a genuine mismatch.
        val state =
            playing(
                eventsSinceConfirm = listOf(lift("e2"), place("e5")),
                latestOccupancy = startOccupancy(),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertEquals(RejectionReason.ILLEGAL, playingAfter(result).rejection)
    }

    @Test
    fun confirmWhileRecoveringIsBlockedButRequestsASnapshot() {
        // The acceptance gate holds: even a would-be-legal sequence can't commit while recovering; a
        // confirm instead pulls a snapshot so restore-verify can clear the gate without the grid.
        val state =
            playing(
                recovering = true,
                rejection = RejectionReason.ILLEGAL,
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertTrue(playingAfter(result).recovering, "still paused — no move committed")
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
        assertTrue(result.effects.none { it is PhysicalEffect.CommitMove })
    }

    @Test
    fun liftAndPlaceWhileRecoveringDoNotBuildAMove() {
        // Restoration lift/place are not a new move: accumulation is short-circuited while recovering,
        // so the snapshot occupancy (not these deltas) is what clears the gate.
        val state = playing(recovering = true)
        assertEquals(state, reduce(state, PhysicalMsg.SquareLifted(sq("e2"))).state)
        assertEquals(state, reduce(state, PhysicalMsg.SquarePlaced(sq("e4"))).state)
    }

    @Test
    fun snapshotKeepsLatestOccupancyFreshEvenMidSequence() {
        // The grid + the fork need a fresh occupancy on every snapshot; the setup-mismatch compare is
        // still suppressed mid-sequence (a snapshot with a move in hand is expected to differ).
        val state = playing(eventsSinceConfirm = listOf(lift("e2")))
        val playing = playingAfter(reduce(state, PhysicalMsg.SnapshotReceived(occupancy = 123L)))
        assertEquals(123L, playing.latestOccupancy)
        assertTrue(!playing.setupMismatch, "no setup-mismatch recompute while a move is in hand")
    }

    @Test
    fun restoringTheExactPositionClearsTheGateAndExitsDiagnosticMode() {
        // The hard restore gate: recovering clears ONLY on an exact occupancy match, which also drops
        // the rejection, closes a manually-opened grid, exits DIAGNOSTIC mode, and re-pulls a snapshot.
        val state =
            playing(
                recovering = true,
                rejection = RejectionReason.ILLEGAL,
                manualDiagnostics = true,
            )
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        val playing = playingAfter(result)
        assertTrue(!playing.recovering)
        assertNull(playing.rejection)
        assertTrue(!playing.manualDiagnostics)
        assertTrue(!playing.diagnosticsVisible)
        assertEquals(
            listOf(
                PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME)),
                PhysicalEffect.Send(BoardCommand.RequestSnapshot),
            ),
            result.effects,
        )
    }

    @Test
    fun aStillMismatchingSnapshotKeepsTheGateClosed() {
        // While the board is wrong, recovering holds and the rejection stays surfaced; the persistent
        // mismatch also auto-opens the grid (setup-mismatch edge).
        val state = playing(recovering = true, rejection = RejectionReason.INCONSISTENT)
        val playing = playingAfter(reduce(state, PhysicalMsg.SnapshotReceived(occupancy = 0L)))
        assertTrue(playing.recovering)
        assertEquals(RejectionReason.INCONSISTENT, playing.rejection)
        assertTrue(playing.diagnosticsVisible)
    }

    @Test
    fun setupMismatchEdgeEntersAndExitsDiagnosticModeOncePerEdge() {
        // FR-011 auto-entry: the false→true setup-mismatch edge enters DIAGNOSTIC, the true→false edge
        // returns to GAME — one SetMode per edge, not per snapshot.
        val entered = reduce(playing(), PhysicalMsg.SnapshotReceived(occupancy = 0L))
        assertTrue(playingAfter(entered).setupMismatch)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))), entered.effects)
        val exited = reduce(playingAfter(entered), PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        assertTrue(!playingAfter(exited).setupMismatch)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME))), exited.effects)
    }

    @Test
    fun showDiagnosticsEntersDiagnosticModeAndPullsASnapshot() {
        val result = reduce(playing(), PhysicalMsg.ShowDiagnostics)
        val playing = playingAfter(result)
        assertTrue(playing.manualDiagnostics)
        assertTrue(playing.diagnosticsVisible)
        assertEquals(
            listOf(
                PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)),
                PhysicalEffect.Send(BoardCommand.RequestSnapshot),
            ),
            result.effects,
        )
    }

    @Test
    fun showDiagnosticsIsANoOpWhenTheGridIsAlreadyAutoShown() {
        // A live setup-mismatch already opened the grid (DIAGNOSTIC mode on); the manual CTA must not
        // re-send SetMode/RequestSnapshot — no per-edge spam.
        val result = reduce(playing(setupMismatch = true), PhysicalMsg.ShowDiagnostics)
        assertTrue(playingAfter(result).manualDiagnostics)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun hideDiagnosticsReturnsToGameModeWhenFullyHidden() {
        val result = reduce(playing(manualDiagnostics = true), PhysicalMsg.HideDiagnostics)
        assertTrue(!playingAfter(result).manualDiagnostics)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME))), result.effects)
    }

    @Test
    fun hideDiagnosticsKeepsTheGridWhenASetupMismatchStillNeedsIt() {
        // Closing the manual grid while the board still mismatches keeps DIAGNOSTIC mode on.
        val result = reduce(playing(manualDiagnostics = true, setupMismatch = true), PhysicalMsg.HideDiagnostics)
        assertTrue(playingAfter(result).diagnosticsVisible)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun reconnectReArmsDiagnosticModeWhenTheGridIsOpen() {
        // The board resets to GAME on every reconnect (contract §1.7); if the grid is open we must
        // re-arm DIAGNOSTIC in addition to the usual snapshot re-request.
        val state = playing(connectionState = BoardConnectionState.DISCONNECTED, manualDiagnostics = true)
        val result = reduce(state, PhysicalMsg.BoardConnected)
        assertEquals(
            listOf(
                PhysicalEffect.Send(BoardCommand.RequestSnapshot),
                PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC)),
            ),
            result.effects,
        )
    }

    @Test
    fun aCommittedMoveClearsTheRecoveryAndDiagnosticsFlags() {
        // Defensive: a successful move resets the gate + grid flags alongside the sequence, so a stale
        // recovering / manualDiagnostics can never survive an accepted move.
        val state =
            playing(
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
                recovering = true,
                manualDiagnostics = true,
            )
        val playing = playingAfter(reduce(state, PhysicalMsg.MoveCommitted(positionAfter("1. e4"), "e4")))
        assertTrue(!playing.recovering)
        assertTrue(!playing.manualDiagnostics)
        assertEquals(2, playing.positions.size)
    }

    // --- S-08: resume gate (FR-013) — set on load, cleared by the shared board-match seam ---

    @Test
    fun resumeWhileDisconnectedGatesAcceptanceWithoutRequestingASnapshot() {
        // A cold resume before the board connects: the gate is set, but the snapshot request is the
        // BoardConnected arm's job once the board comes up — Loaded only requests when already connected.
        val result =
            reduce(
                PhysicalPlayState.Loading,
                PhysicalMsg.Loaded(
                    positions = listOf(Position.start()),
                    sanMoves = emptyList(),
                    whiteLabel = "Alice",
                    blackLabel = "Bob",
                    status = GameStatus.Ongoing,
                    result = null,
                    connected = false,
                ),
            )
        val playing = playingAfter(result)
        assertTrue(playing.awaitingResumeConfirm, "an in-progress resume gates acceptance even before connect")
        assertTrue(playing.acceptanceBlocked)
        assertTrue(result.effects.isEmpty(), "the BoardConnected arm pulls the snapshot once connected")
    }

    @Test
    fun aMatchingSnapshotOnResumeClearsTheGateWithNoDiagnosticsAndNoSetMode() {
        // Auto-resume-on-match: the board already matches the rebuilt position, so the gate clears with no
        // extra input. The board never left GAME mode, so NO SetMode is emitted (the clean-match path).
        val state = playing(awaitingResumeConfirm = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        val playing = playingAfter(result)
        assertTrue(!playing.awaitingResumeConfirm, "an exact match clears the resume gate")
        assertTrue(!playing.acceptanceBlocked)
        assertTrue(!playing.diagnosticsVisible)
        assertTrue(result.effects.isEmpty(), "a clean match leaves the board in GAME mode — no SetMode")
    }

    @Test
    fun aMismatchingSnapshotOnResumeKeepsTheGateBlockedAndOpensDiagnostics() {
        // A mismatch holds the gate, auto-opens the reed grid (setupMismatch edge), and enters DIAGNOSTIC.
        val state = playing(awaitingResumeConfirm = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = 0L))
        val playing = playingAfter(result)
        assertTrue(playing.awaitingResumeConfirm, "a mismatching board keeps the resume gate closed")
        assertTrue(playing.acceptanceBlocked)
        assertTrue(playing.setupMismatch)
        assertTrue(playing.diagnosticsVisible)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))), result.effects)
    }

    @Test
    fun restoringTheBoardAfterAResumeMismatchClearsTheGateWithExactlyOneSetModeGame() {
        // The restore path: the mismatch already opened the grid; the player restores the board and the
        // matching snapshot clears the gate and exits DIAGNOSTIC via the shown→hidden edge — one SetMode(GAME).
        val state = playing(awaitingResumeConfirm = true, setupMismatch = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        val playing = playingAfter(result)
        assertTrue(!playing.awaitingResumeConfirm)
        assertTrue(!playing.acceptanceBlocked)
        assertTrue(!playing.diagnosticsVisible)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME))), result.effects)
    }

    @Test
    fun aManuallyFinishedGameDoesNotEnterTheResumeGate() {
        // FR-018 manual end: result != null but the status is NOT terminal — a terminal-only predicate would
        // miss it and wrongly gate a finished game. The pinned `result == null && !terminal` predicate skips it.
        val result =
            reduce(
                PhysicalPlayState.Loading,
                PhysicalMsg.Loaded(
                    positions = listOf(Position.start()),
                    sanMoves = emptyList(),
                    whiteLabel = "Alice",
                    blackLabel = "Bob",
                    status = GameStatus.Ongoing,
                    result = GameResult.DRAW,
                    connected = true,
                ),
            )
        val playing = playingAfter(result)
        assertTrue(!playing.awaitingResumeConfirm, "a finished record is opened read-only, never gated")
        assertTrue(result.effects.isEmpty(), "no resume snapshot request for a finished game")
    }

    @Test
    fun aTerminallyFinishedGameDoesNotEnterTheResumeGate() {
        // A checkmate position is terminal; even with result == null (defensive open) the gate is skipped.
        val mate = positionAfter("1. f3 e5 2. g4 Qh4#")
        val result =
            reduce(
                PhysicalPlayState.Loading,
                PhysicalMsg.Loaded(
                    positions = listOf(mate),
                    sanMoves = listOf("f3", "e5", "g4", "Qh4#"),
                    whiteLabel = "Alice",
                    blackLabel = "Bob",
                    status = status(mate),
                    result = null,
                    connected = true,
                ),
            )
        assertTrue(!playingAfter(result).awaitingResumeConfirm)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun confirmWhileAwaitingResumeConfirmIsBlockedButRequestsASnapshot() {
        // The acceptance gate holds on resume exactly as on recovery: a confirm can't advance the game
        // until the board is verified, but it re-pulls a snapshot so the match can clear the gate.
        val state =
            playing(
                awaitingResumeConfirm = true,
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertTrue(playingAfter(result).awaitingResumeConfirm, "still gated — no move committed")
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
        assertTrue(result.effects.none { it is PhysicalEffect.CommitMove })
    }

    @Test
    fun liftAndPlaceWhileAwaitingResumeConfirmDoNotBuildAMove() {
        // Restoration lift/place during resume are not a new move (mirrors recovery): accumulation is
        // short-circuited so the snapshot occupancy — not these deltas — is what clears the gate.
        val state = playing(awaitingResumeConfirm = true)
        assertEquals(state, reduce(state, PhysicalMsg.SquareLifted(sq("e2"))).state)
        assertEquals(state, reduce(state, PhysicalMsg.SquarePlaced(sq("e4"))).state)
    }

    // --- S-09: reconnect-reconcile gate (FR-012) — armed on BoardConnected, cleared by the shared seam ---

    @Test
    fun boardConnectedArmsTheReconnectGateAndRequestsASnapshot() {
        // FR-012: every BLE (re)connect holds acceptance from CONNECTED until the post-reconnect snapshot
        // confirms the board still matches — it may have changed while out of range.
        val result = reduce(playing(connectionState = BoardConnectionState.DISCONNECTED), PhysicalMsg.BoardConnected)
        val playing = playingAfter(result)
        assertTrue(playing.reconnectReconciling, "a reconnect arms the reconcile gate")
        assertTrue(!playing.paused, "the board is connected again")
        assertTrue(playing.acceptanceBlocked, "acceptance stays blocked by the gate even though connected")
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
    }

    @Test
    fun aMatchingSnapshotAfterReconnectClearsTheGateWithNoDiagnosticsAndNoSetMode() {
        // Auto-reconnect-on-match: the board still matches the live position, so the gate clears with no extra
        // input and the board never left GAME mode — NO SetMode is emitted (the clean-match path).
        val state = playing(reconnectReconciling = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        val playing = playingAfter(result)
        assertTrue(!playing.reconnectReconciling, "an exact match clears the reconnect gate")
        assertTrue(!playing.acceptanceBlocked)
        assertTrue(!playing.diagnosticsVisible)
        assertTrue(result.effects.isEmpty(), "a clean match leaves the board in GAME mode — no SetMode")
    }

    @Test
    fun aMismatchingSnapshotAfterReconnectKeepsTheGateBlockedAndOpensDiagnostics() {
        // An offline board change holds the gate, auto-opens the reed grid (setupMismatch edge), enters DIAGNOSTIC.
        val state = playing(reconnectReconciling = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = 0L))
        val playing = playingAfter(result)
        assertTrue(playing.reconnectReconciling, "a mismatching board keeps the reconnect gate closed")
        assertTrue(playing.acceptanceBlocked)
        assertTrue(playing.setupMismatch)
        assertTrue(playing.diagnosticsVisible)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.DIAGNOSTIC))), result.effects)
    }

    @Test
    fun restoringTheBoardAfterAReconnectMismatchClearsTheGateWithExactlyOneSetModeGame() {
        // The restore path: the mismatch already opened the grid; restoring the board delivers the matching
        // snapshot that clears the gate and exits DIAGNOSTIC via the shown→hidden edge — one SetMode(GAME).
        val state = playing(reconnectReconciling = true, setupMismatch = true)
        val result = reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        val playing = playingAfter(result)
        assertTrue(!playing.reconnectReconciling)
        assertTrue(!playing.acceptanceBlocked)
        assertTrue(!playing.diagnosticsVisible)
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.SetMode(BoardMode.GAME))), result.effects)
    }

    @Test
    fun backToBackDuplicatePostReconnectSnapshotsAreIdempotent() {
        // The reconnect burst snapshot and the BoardConnected arm's RequestSnapshot can both deliver the same
        // snapshot. The first match clears the gate; the duplicate is a pure no-op — gate stays clear, no SetMode.
        val state = playing(reconnectReconciling = true)
        val first = playingAfter(reduce(state, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy())))
        assertTrue(!first.reconnectReconciling, "the first matching snapshot clears the gate")
        val second = reduce(first, PhysicalMsg.SnapshotReceived(occupancy = startOccupancy()))
        assertTrue(!playingAfter(second).reconnectReconciling, "a duplicate snapshot keeps the gate clear")
        assertTrue(!playingAfter(second).setupMismatch)
        assertTrue(second.effects.isEmpty(), "the duplicate fires no SetMode — idempotent")
    }

    @Test
    fun confirmWhileReconnectReconcilingIsBlockedButRequestsASnapshot() {
        // The acceptance gate holds on reconnect exactly as on resume/recovery: a confirm can't advance the
        // game until the board is verified, but it re-pulls a snapshot so the match can clear the gate.
        val state =
            playing(
                reconnectReconciling = true,
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
            )
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        assertTrue(playingAfter(result).reconnectReconciling, "still gated — no move committed")
        assertEquals(listOf(PhysicalEffect.Send(BoardCommand.RequestSnapshot)), result.effects)
        assertTrue(result.effects.none { it is PhysicalEffect.CommitMove })
    }

    @Test
    fun liftAndPlaceWhileReconnectReconcilingDoNotBuildAMove() {
        // Restoration lift/place during a reconnect are not a new move (mirrors resume/recovery): accumulation
        // is short-circuited so the snapshot occupancy — not these deltas — is what clears the gate.
        val state = playing(reconnectReconciling = true)
        assertEquals(state, reduce(state, PhysicalMsg.SquareLifted(sq("e2"))).state)
        assertEquals(state, reduce(state, PhysicalMsg.SquarePlaced(sq("e4"))).state)
    }

    @Test
    fun aCommittedMoveClearsTheReconnectGate() {
        // Defensive: the gate blocks confirm/commit, but a committed move still resets it alongside the other
        // gates, so a stale reconnectReconciling can never survive an accepted move.
        val state =
            playing(
                eventsSinceConfirm = listOf(lift("e2"), place("e4")),
                reconnectReconciling = true,
            )
        val playing = playingAfter(reduce(state, PhysicalMsg.MoveCommitted(positionAfter("1. e4"), "e4")))
        assertTrue(!playing.reconnectReconciling)
        assertEquals(2, playing.positions.size)
    }
}
