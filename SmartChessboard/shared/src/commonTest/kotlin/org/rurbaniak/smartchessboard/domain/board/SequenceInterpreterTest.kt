package org.rurbaniak.smartchessboard.domain.board

import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fen
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.pgn.parsePgn
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Locks the resolution contract against every move shape and failure mode, on all three targets.
// Event lists are built raw (the interpreter is pure: List<SquareEvent> in, Resolution out) using the
// same orderings the chess-agnostic BoardScenarios DSL produces against a real EmulatedBoard — capture
// CAPTURED_FIRST / MOVER_FIRST, castling KING_FIRST / ROOK_FIRST / INTERLEAVED — so what is proven here
// is exactly what the emulator-driven E2E (Phase 5) will feed through the live ViewModel.
class SequenceInterpreterTest {
    private fun sq(name: String): Int = squareOf(name[0] - 'a', name[1] - '1')

    private fun lift(name: String) = BoardEvent.SquareEvent(sq(name), SquareEventType.LIFT)

    private fun place(name: String) = BoardEvent.SquareEvent(sq(name), SquareEventType.PLACE)

    private fun positionAfter(pgn: String): Position {
        val game = parsePgn(pgn)
        assertNull(game.truncation, "setup PGN must parse completely, got ${game.truncation}")
        return game.positions.last()
    }

    private fun assertResolved(
        position: Position,
        events: List<BoardEvent.SquareEvent>,
        expected: Move,
    ) {
        val resolution = resolvePhysicalMove(position, events)
        assertIs<Resolution.Resolved>(resolution, "expected $expected, got $resolution")
        assertEquals(expected, resolution.move)
        // The resolved move must always come from the engine's legal set, never be hand-built (§1.6).
        assertTrue(resolution.move in legalMoves(position), "resolved move must be a legalMoves entry")
    }

    // --- Quiet move ---

    @Test
    fun quietMoveResolvesToTheLiftPlacePair() {
        assertResolved(Position.start(), listOf(lift("e2"), place("e4")), Move(sq("e2"), sq("e4")))
    }

    @Test
    fun quietPieceMoveResolves() {
        assertResolved(Position.start(), listOf(lift("g1"), place("f3")), Move(sq("g1"), sq("f3")))
    }

    // --- Captures (the snapshot-blind case): both physical orderings resolve identically ---

    @Test
    fun pawnCaptureCapturedFirstResolves() {
        // 1. e4 d5 — white to move, exd5; the captured pawn is lifted before the mover.
        val position = positionAfter("1. e4 d5")
        assertResolved(
            position,
            listOf(lift("d5"), lift("e4"), place("d5")),
            Move(sq("e4"), sq("d5")),
        )
    }

    @Test
    fun pieceCaptureMoverFirstResolves() {
        // 1. e4 d5 2. exd5 — black to move, Qxd5; the mover is lifted before the captured pawn.
        val position = positionAfter("1. e4 d5 2. exd5")
        assertResolved(
            position,
            listOf(lift("d8"), lift("d5"), place("d5")),
            Move(sq("d8"), sq("d5")),
        )
    }

    // --- Castling: three physical orderings, both sides ---

    private val whiteCastleReady = fen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq -")
    private val blackCastleReady = fen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq -")

    @Test
    fun whiteKingsideCastleKingFirstResolves() {
        assertResolved(
            whiteCastleReady,
            listOf(lift("e1"), place("g1"), lift("h1"), place("f1")),
            Move(sq("e1"), sq("g1")),
        )
    }

    @Test
    fun whiteKingsideCastleRookFirstResolves() {
        assertResolved(
            whiteCastleReady,
            listOf(lift("h1"), place("f1"), lift("e1"), place("g1")),
            Move(sq("e1"), sq("g1")),
        )
    }

    @Test
    fun whiteKingsideCastleInterleavedResolves() {
        // Both pieces airborne before either lands — the messiest ordering a reed matrix sees.
        assertResolved(
            whiteCastleReady,
            listOf(lift("e1"), lift("h1"), place("g1"), place("f1")),
            Move(sq("e1"), sq("g1")),
        )
    }

    @Test
    fun whiteQueensideCastleResolves() {
        assertResolved(
            whiteCastleReady,
            listOf(lift("e1"), place("c1"), lift("a1"), place("d1")),
            Move(sq("e1"), sq("c1")),
        )
    }

    @Test
    fun blackKingsideCastleResolves() {
        assertResolved(
            blackCastleReady,
            listOf(lift("h8"), place("f8"), lift("e8"), place("g8")),
            Move(sq("e8"), sq("g8")),
        )
    }

    @Test
    fun blackQueensideCastleInterleavedResolves() {
        assertResolved(
            blackCastleReady,
            listOf(lift("e8"), lift("a8"), place("c8"), place("d8")),
            Move(sq("e8"), sq("c8")),
        )
    }

    // --- En passant: the captured pawn is not on the landing square; both orderings resolve ---

    @Test
    fun enPassantCapturedFirstResolves() {
        // 1. e4 Nf6 2. e5 d5 — white to move, exd6 e.p.; the d5 pawn is captured off the d6 landing square.
        val position = positionAfter("1. e4 Nf6 2. e5 d5")
        assertResolved(
            position,
            listOf(lift("d5"), lift("e5"), place("d6")),
            Move(sq("e5"), sq("d6")),
        )
    }

    @Test
    fun enPassantMoverFirstResolves() {
        val position = positionAfter("1. e4 Nf6 2. e5 d5")
        assertResolved(
            position,
            listOf(lift("e5"), lift("d5"), place("d6")),
            Move(sq("e5"), sq("d6")),
        )
    }

    // --- Promotion: surfaced as NeedsPromotion (the four promo moves collapse to one from/to) ---

    @Test
    fun promotionPushNeedsPromotion() {
        val position = fen("k7/3P4/8/8/8/8/8/7K w - -")
        val resolution = resolvePhysicalMove(position, listOf(lift("d7"), place("d8")))
        assertEquals(Resolution.NeedsPromotion(sq("d7"), sq("d8")), resolution)
    }

    @Test
    fun promotionCaptureNeedsPromotion() {
        // White pawn e7 takes the d8 rook while promoting; e7-e8 push promo also exists but the
        // capture events match only the e7xd8 footprint.
        val position = fen("3r3k/4P3/8/8/8/8/8/7K w - -")
        val resolution = resolvePhysicalMove(position, listOf(lift("d8"), lift("e7"), place("d8")))
        assertEquals(Resolution.NeedsPromotion(sq("e7"), sq("d8")), resolution)
    }

    // --- j'adoube / sensor noise: a lift-and-replace on the same square must not break resolution ---

    @Test
    fun jadoubeBeforeARealMoveIsIgnored() {
        // The player nudges the g1 knight back into place, then actually plays e4 — the blip is noise.
        assertResolved(
            Position.start(),
            listOf(lift("g1"), place("g1"), lift("e2"), place("e4")),
            Move(sq("e2"), sq("e4")),
        )
    }

    @Test
    fun jadoubeWithNoMoveIsIncomplete() {
        // Adjusting a piece and setting it back down, with no move played, resolves to nothing.
        val resolution = resolvePhysicalMove(Position.start(), listOf(lift("e2"), place("e2")))
        assertEquals(Resolution.Incomplete, resolution)
    }

    // --- Rejections ---

    @Test
    fun aLoneLiftIsIncomplete() {
        // A piece is in hand; the move is not finished.
        val resolution = resolvePhysicalMove(Position.start(), listOf(lift("e2")))
        assertEquals(Resolution.Incomplete, resolution)
    }

    @Test
    fun noEventsAreIncomplete() {
        val resolution = resolvePhysicalMove(Position.start(), emptyList())
        assertEquals(Resolution.Incomplete, resolution)
    }

    @Test
    fun anOffBoardLandingIsIllegal() {
        // The g1 knight is set down on e4, a square it cannot reach — a completed-looking, illegal move.
        val resolution = resolvePhysicalMove(Position.start(), listOf(lift("g1"), place("e4")))
        assertEquals(Resolution.Illegal, resolution)
    }

    @Test
    fun anIllegalCaptureIsIllegal() {
        // 1. e4 e5 — the e4 pawn "captures" the e5 pawn straight ahead, which pawns cannot do.
        val position = positionAfter("1. e4 e5")
        val resolution = resolvePhysicalMove(position, listOf(lift("e5"), lift("e4"), place("e5")))
        assertEquals(Resolution.Illegal, resolution)
    }

    // --- The hardest bet: near-ambiguity resolves uniquely from the origin lift ---

    @Test
    fun twoKnightsToTheSameSquareResolveByTheLiftedOriginNotAmbiguous() {
        // Both knights reach d5; a snapshot of the final board cannot tell which moved, but the lift on
        // the origin does. This is why the resolver's Ambiguous branch is unreachable under a full stream.
        // Substitutes the plan's enumerated "two legal moves -> Ambiguous" corpus case: that outcome is
        // not constructible from a real lift/place stream, so this proves unique resolution instead.
        val position = fen("7k/8/8/8/8/2N1N3/8/7K w - -")
        assertTrue(Move(sq("c3"), sq("d5")) in legalMoves(position))
        assertTrue(Move(sq("e3"), sq("d5")) in legalMoves(position))

        assertResolved(position, listOf(lift("c3"), place("d5")), Move(sq("c3"), sq("d5")))
        assertResolved(position, listOf(lift("e3"), place("d5")), Move(sq("e3"), sq("d5")))
    }

    // --- Occupancy helper (Position.toOccupancy) ---

    @Test
    fun startPositionOccupancyIsRanksOneTwoSevenEight() {
        // Ranks 1, 2, 7, 8 occupied (squares 0–15 and 48–63) — the chess start layout as a bit pattern.
        val expected = 0xFFFFL or (0xFFFFL shl 48)
        assertEquals(expected, Position.start().toOccupancy())
    }

    @Test
    fun occupancyIsInverseCompatibleWithBoardSnapshot() {
        val position = positionAfter("1. e4 e5 2. Nf3 Nc6")
        val snapshot = BoardEvent.BoardSnapshot(position.toOccupancy())
        for (square in 0 until 64) {
            assertEquals(position.pieceAt(square) != null, snapshot.isOccupied(square), "square $square")
        }
    }
}
