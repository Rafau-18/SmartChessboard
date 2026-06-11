package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessRulesTest {
    private fun applied(
        position: Position,
        move: Move,
    ): Position {
        val outcome = validate(position, move)
        assertIs<MoveOutcome.Legal>(outcome, "expected $move to be legal")
        return outcome.position
    }

    // --- Legality filter: pins & check evasion ---

    @Test
    fun absolutelyPinnedRookCannotLeaveTheFile() {
        val e1 = squareOf(4, 0)
        val e2 = squareOf(4, 1)
        val e8 = squareOf(4, 7)
        val position =
            positionOf(
                e1 to white(PieceType.KING),
                e2 to white(PieceType.ROOK),
                e8 to black(PieceType.ROOK),
                squareOf(7, 7) to black(PieceType.KING),
            )
        val legal = legalMoves(position)
        // Sideways rook moves would expose the king — all rejected.
        assertTrue(legal.none { it.from == e2 && fileOf(it.to) != 4 })
        // Along the pin line (including capturing the pinning rook) stays legal.
        assertContains(legal, Move(e2, squareOf(4, 3)))
        assertContains(legal, Move(e2, e8))
    }

    @Test
    fun whenInCheckOnlyEvadingMovesAreLegal() {
        val e1 = squareOf(4, 0)
        val d1 = squareOf(3, 0)
        val e8 = squareOf(4, 7)
        val position =
            positionOf(
                e1 to white(PieceType.KING),
                d1 to white(PieceType.QUEEN),
                e8 to black(PieceType.ROOK),
                squareOf(7, 7) to black(PieceType.KING),
            )
        val legal = legalMoves(position)
        // Blocking the check is legal; an unrelated queen move is not.
        assertContains(legal, Move(d1, squareOf(4, 1)))
        assertFalse(Move(d1, squareOf(0, 3)) in legal)
        // The king may not step to another attacked square on the e-file.
        assertFalse(Move(e1, squareOf(4, 1)) in legal)
    }

    @Test
    fun kingCannotMoveIntoAttack() {
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(3, 7) to black(PieceType.ROOK),
                squareOf(7, 7) to black(PieceType.KING),
            )
        val legal = legalMoves(position)
        assertTrue(legal.none { fileOf(it.to) == 3 })
    }

    // --- Castling legality ---

    private fun whiteCastlingPosition(vararg extras: Pair<Int, Piece>) =
        positionOf(
            squareOf(4, 0) to white(PieceType.KING),
            squareOf(0, 0) to white(PieceType.ROOK),
            squareOf(7, 0) to white(PieceType.ROOK),
            squareOf(4, 7) to black(PieceType.KING),
            *extras,
            castlingRights = CastlingRights(true, true, false, false),
        )

    @Test
    fun castlingLegalBothSidesWhenNothingInterferes() {
        val legal = legalMoves(whiteCastlingPosition())
        assertContains(legal, Move(squareOf(4, 0), squareOf(6, 0)))
        assertContains(legal, Move(squareOf(4, 0), squareOf(2, 0)))
    }

    @Test
    fun blackCastlingLegalBothSidesWhenNothingInterferes() {
        val e8 = squareOf(4, 7)
        val position =
            positionOf(
                e8 to black(PieceType.KING),
                squareOf(0, 7) to black(PieceType.ROOK),
                squareOf(7, 7) to black(PieceType.ROOK),
                squareOf(4, 0) to white(PieceType.KING),
                sideToMove = Color.BLACK,
                castlingRights = CastlingRights(false, false, true, true),
            )
        val legal = legalMoves(position)
        assertContains(legal, Move(e8, squareOf(6, 7)))
        assertContains(legal, Move(e8, squareOf(2, 7)))
    }

    @Test
    fun castlingRejectedWhileInCheck() {
        val legal = legalMoves(whiteCastlingPosition(squareOf(4, 5) to black(PieceType.ROOK)))
        assertTrue(legal.none { it.from == squareOf(4, 0) && it.to in setOf(squareOf(6, 0), squareOf(2, 0)) })
    }

    @Test
    fun castlingRejectedThroughAttackedTransitSquare() {
        // Black rook on f6 attacks f1 (king side) — d6 rook would attack d1 (queen side).
        val kingSideBlocked = legalMoves(whiteCastlingPosition(squareOf(5, 5) to black(PieceType.ROOK)))
        assertFalse(Move(squareOf(4, 0), squareOf(6, 0)) in kingSideBlocked)
        assertContains(kingSideBlocked, Move(squareOf(4, 0), squareOf(2, 0)))

        val queenSideBlocked = legalMoves(whiteCastlingPosition(squareOf(3, 5) to black(PieceType.ROOK)))
        assertFalse(Move(squareOf(4, 0), squareOf(2, 0)) in queenSideBlocked)
        assertContains(queenSideBlocked, Move(squareOf(4, 0), squareOf(6, 0)))
    }

    @Test
    fun castlingRejectedIntoAttackedDestination() {
        // Black rook on g6 attacks g1; c6 rook attacks c1.
        val kingSideBlocked = legalMoves(whiteCastlingPosition(squareOf(6, 5) to black(PieceType.ROOK)))
        assertFalse(Move(squareOf(4, 0), squareOf(6, 0)) in kingSideBlocked)

        val queenSideBlocked = legalMoves(whiteCastlingPosition(squareOf(2, 5) to black(PieceType.ROOK)))
        assertFalse(Move(squareOf(4, 0), squareOf(2, 0)) in queenSideBlocked)
    }

    @Test
    fun queenSideCastlingIgnoresAttackOnTheRookTransitSquare() {
        // b1 is crossed only by the rook, not the king — an attack on b1 must not block O-O-O.
        val legal = legalMoves(whiteCastlingPosition(squareOf(1, 5) to black(PieceType.ROOK)))
        assertContains(legal, Move(squareOf(4, 0), squareOf(2, 0)))
    }

    // --- En passant ---

    @Test
    fun enPassantCaptureRemovesThePawnBesideTheDestination() {
        val e5 = squareOf(4, 4)
        val d5 = squareOf(3, 4)
        val d6 = squareOf(3, 5)
        val position =
            positionOf(
                e5 to white(PieceType.PAWN),
                d5 to black(PieceType.PAWN),
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(4, 7) to black(PieceType.KING),
                enPassantTarget = d6,
            )
        val next = applied(position, Move(e5, d6))
        assertEquals(white(PieceType.PAWN), next.pieceAt(d6))
        assertNull(next.pieceAt(d5), "the en-passant-captured pawn must be removed")
        assertNull(next.pieceAt(e5))
        assertEquals(0, next.halfmoveClock, "en passant is a pawn move and a capture")
    }

    @Test
    fun enPassantRejectedWhenItDiscoversCheckAlongTheRank() {
        // Removing both pawns from rank 5 would expose the white king on h5 to the a5 rook.
        val e5 = squareOf(4, 4)
        val d5 = squareOf(3, 4)
        val position =
            positionOf(
                e5 to white(PieceType.PAWN),
                d5 to black(PieceType.PAWN),
                squareOf(7, 4) to white(PieceType.KING),
                squareOf(0, 4) to black(PieceType.ROOK),
                squareOf(0, 7) to black(PieceType.KING),
                enPassantTarget = squareOf(3, 5),
            )
        val outcome = validate(position, Move(e5, squareOf(3, 5)))
        assertIs<MoveOutcome.Illegal>(outcome)
        assertEquals(IllegalReason.NO_SUCH_MOVE, outcome.reason)
    }

    // --- Promotion via validate ---

    @Test
    fun promotionOffersExactlyTheFourTargetPieces() {
        val a7 = squareOf(0, 6)
        val a8 = squareOf(0, 7)
        val position =
            positionOf(
                a7 to white(PieceType.PAWN),
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(4, 7) to black(PieceType.KING),
            )
        val promotions = legalMoves(position).filter { it.from == a7 && it.to == a8 }
        assertEquals(4, promotions.size)
        assertEquals(PROMOTION_TARGETS, promotions.mapNotNull { it.promoteTo }.toSet())
    }

    @Test
    fun incompletePromotionIsRejectedWithDedicatedReason() {
        val a7 = squareOf(0, 6)
        val position =
            positionOf(
                a7 to white(PieceType.PAWN),
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(4, 7) to black(PieceType.KING),
            )
        val outcome = validate(position, Move(a7, squareOf(0, 7)))
        assertIs<MoveOutcome.Illegal>(outcome)
        assertEquals(IllegalReason.PROMOTION_PIECE_REQUIRED, outcome.reason)
    }

    @Test
    fun completedPromotionReplacesThePawn() {
        val a7 = squareOf(0, 6)
        val a8 = squareOf(0, 7)
        val position =
            positionOf(
                a7 to white(PieceType.PAWN),
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(4, 7) to black(PieceType.KING),
            )
        val next = applied(position, Move(a7, a8, PieceType.QUEEN))
        assertEquals(white(PieceType.QUEEN), next.pieceAt(a8))
        assertNull(next.pieceAt(a7))
    }

    @Test
    fun promotionPieceOnANonPromotionMoveIsNoSuchMove() {
        val outcome = validate(Position.start(), Move(squareOf(4, 1), squareOf(4, 2), PieceType.QUEEN))
        assertIs<MoveOutcome.Illegal>(outcome)
        assertEquals(IllegalReason.NO_SUCH_MOVE, outcome.reason)
    }

    @Test
    fun arbitraryIllegalAttemptIsNoSuchMove() {
        val outcome = validate(Position.start(), Move(squareOf(4, 1), squareOf(4, 4)))
        assertIs<MoveOutcome.Illegal>(outcome)
        assertEquals(IllegalReason.NO_SUCH_MOVE, outcome.reason)
    }

    // --- applyMove state transitions ---

    @Test
    fun doublePushSetsEnPassantTargetForExactlyOnePly() {
        val afterWhite = applied(Position.start(), Move(squareOf(4, 1), squareOf(4, 3)))
        assertEquals(squareOf(4, 2), afterWhite.enPassantTarget)
        assertEquals(Color.BLACK, afterWhite.sideToMove)
        assertEquals(0, afterWhite.halfmoveClock)
        assertEquals(1, afterWhite.fullmoveNumber)

        val afterBlack = applied(afterWhite, Move(squareOf(6, 7), squareOf(5, 5)))
        assertNull(afterBlack.enPassantTarget, "a non-double-push move must clear the target")
        assertEquals(Color.WHITE, afterBlack.sideToMove)
        assertEquals(1, afterBlack.halfmoveClock, "quiet knight move increments the clock")
        assertEquals(2, afterBlack.fullmoveNumber, "incremented after Black moves")
    }

    @Test
    fun captureResetsHalfmoveClock() {
        var position = applied(Position.start(), Move(squareOf(4, 1), squareOf(4, 3)))
        position = applied(position, Move(squareOf(3, 6), squareOf(3, 4)))
        position = position.copy(halfmoveClock = 5)
        val next = applied(position, Move(squareOf(4, 3), squareOf(3, 4)))
        assertEquals(0, next.halfmoveClock)
    }

    @Test
    fun kingMoveRevokesBothOfTheMoversRights() {
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(0, 0) to white(PieceType.ROOK),
                squareOf(7, 0) to white(PieceType.ROOK),
                squareOf(4, 7) to black(PieceType.KING),
                castlingRights = CastlingRights.ALL,
            )
        val next = applied(position, Move(squareOf(4, 0), squareOf(4, 1)))
        assertFalse(next.castlingRights.whiteKingSide)
        assertFalse(next.castlingRights.whiteQueenSide)
        assertTrue(next.castlingRights.blackKingSide, "black's rights are untouched")
        assertTrue(next.castlingRights.blackQueenSide)
    }

    @Test
    fun rookMoveRevokesOnlyItsOwnSide() {
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(0, 0) to white(PieceType.ROOK),
                squareOf(7, 0) to white(PieceType.ROOK),
                squareOf(4, 7) to black(PieceType.KING),
                castlingRights = CastlingRights.ALL,
            )
        val next = applied(position, Move(squareOf(0, 0), squareOf(0, 3)))
        assertFalse(next.castlingRights.whiteQueenSide)
        assertTrue(next.castlingRights.whiteKingSide)
    }

    @Test
    fun rookCapturedOnItsHomeSquareRevokesThatRight() {
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(7, 0) to white(PieceType.ROOK),
                squareOf(3, 4) to black(PieceType.BISHOP),
                squareOf(4, 7) to black(PieceType.KING),
                sideToMove = Color.BLACK,
                castlingRights = CastlingRights.ALL,
            )
        val next = applied(position, Move(squareOf(3, 4), squareOf(7, 0)))
        assertFalse(next.castlingRights.whiteKingSide)
        assertTrue(next.castlingRights.whiteQueenSide)
    }

    @Test
    fun castlingRelocatesTheRookOnBothSides() {
        val kingSide = applied(whiteCastlingPosition(), Move(squareOf(4, 0), squareOf(6, 0)))
        assertEquals(white(PieceType.KING), kingSide.pieceAt(squareOf(6, 0)))
        assertEquals(white(PieceType.ROOK), kingSide.pieceAt(squareOf(5, 0)))
        assertNull(kingSide.pieceAt(squareOf(7, 0)))
        assertFalse(kingSide.castlingRights.whiteKingSide)
        assertFalse(kingSide.castlingRights.whiteQueenSide)

        val queenSide = applied(whiteCastlingPosition(), Move(squareOf(4, 0), squareOf(2, 0)))
        assertEquals(white(PieceType.KING), queenSide.pieceAt(squareOf(2, 0)))
        assertEquals(white(PieceType.ROOK), queenSide.pieceAt(squareOf(3, 0)))
        assertNull(queenSide.pieceAt(squareOf(0, 0)))
    }

    @Test
    fun startPositionHasTwentyLegalMoves() {
        assertEquals(20, legalMoves(Position.start()).size)
    }

    // --- Terminal-state classification (status, FR-007) ---

    @Test
    fun startPositionIsOngoing() {
        assertEquals(GameStatus.Ongoing, status(Position.start()))
    }

    @Test
    fun checkWithLegalRepliesIsPlainCheckNotCheckmate() {
        // White is checked by the e8 rook but can block with the queen — Check, not Checkmate.
        val position =
            positionOf(
                squareOf(4, 0) to white(PieceType.KING),
                squareOf(3, 0) to white(PieceType.QUEEN),
                squareOf(4, 7) to black(PieceType.ROOK),
                squareOf(7, 7) to black(PieceType.KING),
            )
        assertEquals(GameStatus.Check, status(position))
    }

    @Test
    fun foolsMateIsCheckmate() {
        // 1. f3 e5 2. g4 Qh4# — the fastest possible checkmate, played through the public API.
        var position = applied(Position.start(), Move(squareOf(5, 1), squareOf(5, 2)))
        position = applied(position, Move(squareOf(4, 6), squareOf(4, 4)))
        position = applied(position, Move(squareOf(6, 1), squareOf(6, 3)))
        position = applied(position, Move(squareOf(3, 7), squareOf(7, 3)))
        assertEquals(GameStatus.Checkmate, status(position))
    }

    @Test
    fun backRankMateIsCheckmate() {
        // White king boxed in by its own pawns; the black rook delivers mate along rank 1.
        val position =
            positionOf(
                squareOf(6, 0) to white(PieceType.KING),
                squareOf(5, 1) to white(PieceType.PAWN),
                squareOf(6, 1) to white(PieceType.PAWN),
                squareOf(7, 1) to white(PieceType.PAWN),
                squareOf(4, 0) to black(PieceType.ROOK),
                squareOf(6, 7) to black(PieceType.KING),
            )
        assertEquals(GameStatus.Checkmate, status(position))
    }

    @Test
    fun kingAndPawnEndgameStalemateIsStalemate() {
        // Classic K+P stalemate: the cornered black king is not in check but has no legal move —
        // a7 is protected, b7 is covered by the white king, b8 by the pawn.
        val position =
            positionOf(
                squareOf(0, 7) to black(PieceType.KING),
                squareOf(0, 6) to white(PieceType.PAWN),
                squareOf(1, 5) to white(PieceType.KING),
                sideToMove = Color.BLACK,
            )
        assertEquals(GameStatus.Stalemate, status(position))
    }
}
