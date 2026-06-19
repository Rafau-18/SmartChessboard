package org.rurbaniak.smartchessboard.presentation.physical

import org.rurbaniak.smartchessboard.domain.board.BoardButton
import org.rurbaniak.smartchessboard.domain.board.BoardCommand
import org.rurbaniak.smartchessboard.domain.board.BoardConnectionState
import org.rurbaniak.smartchessboard.domain.board.BoardEvent
import org.rurbaniak.smartchessboard.domain.board.SquareEventType
import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fen
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
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
        assertTrue(result.effects.isEmpty())
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
    fun illegalSequenceConfirmRejectsAndClearsTheSequence() {
        // e2 to e5 is not a legal pawn move; the resolver returns Illegal.
        val state = playing(eventsSinceConfirm = listOf(lift("e2"), place("e5")))
        val result = reduce(state, PhysicalMsg.ConfirmPressed(BoardButton.WHITE))
        val playing = playingAfter(result)
        assertEquals(RejectionReason.ILLEGAL, playing.rejection)
        assertTrue(playing.eventsSinceConfirm.isEmpty())
        assertTrue(result.effects.isEmpty())
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
}
