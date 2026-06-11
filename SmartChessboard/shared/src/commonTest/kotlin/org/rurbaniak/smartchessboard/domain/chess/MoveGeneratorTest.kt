package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveGeneratorTest {
    @Test
    fun startPositionHasTwentyPseudoLegalMoves() {
        // 16 pawn moves (8 single + 8 double) + 4 knight moves.
        assertEquals(20, pseudoLegalMoves(Position.start()).size)
    }

    @Test
    fun pawnPushesSingleAndDoubleFromHomeRank() {
        val e2 = squareOf(4, 1)
        val moves = pseudoLegalMoves(positionOf(e2 to white(PieceType.PAWN)))
        assertEquals(setOf(Move(e2, squareOf(4, 2)), Move(e2, squareOf(4, 3))), moves.toSet())
    }

    @Test
    fun pawnOffHomeRankHasNoDoublePush() {
        val e3 = squareOf(4, 2)
        val moves = pseudoLegalMoves(positionOf(e3 to white(PieceType.PAWN)))
        assertEquals(listOf(Move(e3, squareOf(4, 3))), moves)
    }

    @Test
    fun blockedPawnGeneratesNoPushes() {
        val e2 = squareOf(4, 1)
        val e3 = squareOf(4, 2)
        val moves = pseudoLegalMoves(positionOf(e2 to white(PieceType.PAWN), e3 to black(PieceType.KNIGHT)))
        assertTrue(moves.none { it.from == e2 })
    }

    @Test
    fun pawnDoublePushBlockedOnLandingSquareStillPushesSingle() {
        val e2 = squareOf(4, 1)
        val e4 = squareOf(4, 3)
        val moves = pseudoLegalMoves(positionOf(e2 to white(PieceType.PAWN), e4 to black(PieceType.KNIGHT)))
        assertEquals(listOf(Move(e2, squareOf(4, 2))), moves)
    }

    @Test
    fun pawnCapturesDiagonallyButNotForward() {
        val e4 = squareOf(4, 3)
        val e5 = squareOf(4, 4)
        val d5 = squareOf(3, 4)
        val f5 = squareOf(5, 4)
        val moves =
            pseudoLegalMoves(
                positionOf(
                    e4 to white(PieceType.PAWN),
                    e5 to black(PieceType.PAWN),
                    d5 to black(PieceType.KNIGHT),
                    f5 to white(PieceType.KNIGHT),
                ),
            )
        val pawnMoves = moves.filter { it.from == e4 }
        // Forward push blocked, own piece on f5 not capturable — only the d5 capture remains.
        assertEquals(listOf(Move(e4, d5)), pawnMoves)
    }

    @Test
    fun blackPawnMovesTowardRankZero() {
        val e7 = squareOf(4, 6)
        val moves = pseudoLegalMoves(positionOf(e7 to black(PieceType.PAWN), sideToMove = Color.BLACK))
        assertEquals(setOf(Move(e7, squareOf(4, 5)), Move(e7, squareOf(4, 4))), moves.toSet())
    }

    @Test
    fun pawnReachingLastRankExpandsToFourPromotions() {
        val a7 = squareOf(0, 6)
        val a8 = squareOf(0, 7)
        val moves = pseudoLegalMoves(positionOf(a7 to white(PieceType.PAWN)))
        assertEquals(4, moves.size)
        assertEquals(PROMOTION_TARGETS, moves.map { it.promoteTo }.toSet())
        assertTrue(moves.all { it.from == a7 && it.to == a8 })
    }

    @Test
    fun capturePromotionAlsoExpandsToFourMoves() {
        val a7 = squareOf(0, 6)
        val b8 = squareOf(1, 7)
        val a8 = squareOf(0, 7)
        val moves =
            pseudoLegalMoves(
                positionOf(a7 to white(PieceType.PAWN), b8 to black(PieceType.ROOK), a8 to black(PieceType.KNIGHT)),
            )
        // Push square occupied — all four moves are the b8 capture-promotion.
        assertEquals(4, moves.size)
        assertTrue(moves.all { it.from == a7 && it.to == b8 })
        assertEquals(PROMOTION_TARGETS, moves.map { it.promoteTo }.toSet())
    }

    @Test
    fun enPassantGeneratedExactlyWhenTargetMatches() {
        val e5 = squareOf(4, 4)
        val d5 = squareOf(3, 4)
        val d6 = squareOf(3, 5)
        val placements = arrayOf(e5 to white(PieceType.PAWN), d5 to black(PieceType.PAWN))
        val withTarget = pseudoLegalMoves(positionOf(*placements, enPassantTarget = d6))
        assertContains(withTarget, Move(e5, d6))
        val withoutTarget = pseudoLegalMoves(positionOf(*placements))
        assertFalse(Move(e5, d6) in withoutTarget)
    }

    @Test
    fun knightJumpsEightWaysFromCenterAndTwoFromCorner() {
        val e4 = squareOf(4, 3)
        assertEquals(8, pseudoLegalMoves(positionOf(e4 to white(PieceType.KNIGHT))).size)
        val a1 = squareOf(0, 0)
        val cornerMoves = pseudoLegalMoves(positionOf(a1 to white(PieceType.KNIGHT)))
        assertEquals(setOf(Move(a1, squareOf(1, 2)), Move(a1, squareOf(2, 1))), cornerMoves.toSet())
    }

    @Test
    fun knightSkipsOwnPieceTargetsButCapturesEnemy() {
        val e4 = squareOf(4, 3)
        val d6 = squareOf(3, 5)
        val f6 = squareOf(5, 5)
        val moves =
            pseudoLegalMoves(
                positionOf(
                    e4 to white(PieceType.KNIGHT),
                    d6 to white(PieceType.PAWN),
                    f6 to black(PieceType.PAWN),
                ),
            )
        val knightMoves = moves.filter { it.from == e4 }
        assertFalse(Move(e4, d6) in knightMoves)
        assertContains(knightMoves, Move(e4, f6))
        assertEquals(7, knightMoves.size)
    }

    @Test
    fun rookRayStopsAtOwnPieceAndCapturesFirstEnemy() {
        val a1 = squareOf(0, 0)
        val a3 = squareOf(0, 2)
        val e1 = squareOf(4, 0)
        val moves =
            pseudoLegalMoves(
                positionOf(
                    a1 to white(PieceType.ROOK),
                    a3 to white(PieceType.PAWN),
                    e1 to black(PieceType.BISHOP),
                ),
            )
        val rookMoves = moves.filter { it.from == a1 }.map { it.to }.toSet()
        // North: a2 only (own pawn at a3 blocks). East: b1, c1, d1, then capture e1 — not beyond.
        assertEquals(setOf(squareOf(0, 1), squareOf(1, 0), squareOf(2, 0), squareOf(3, 0), e1), rookMoves)
    }

    @Test
    fun bishopAndQueenCoverTheirRayCountsFromEmptyCenter() {
        val e4 = squareOf(4, 3)
        assertEquals(13, pseudoLegalMoves(positionOf(e4 to white(PieceType.BISHOP))).size)
        assertEquals(27, pseudoLegalMoves(positionOf(e4 to white(PieceType.QUEEN))).size)
    }

    @Test
    fun kingStepsOneSquareInAllDirections() {
        val e4 = squareOf(4, 3)
        assertEquals(8, pseudoLegalMoves(positionOf(e4 to white(PieceType.KING))).size)
        val a1 = squareOf(0, 0)
        assertEquals(3, pseudoLegalMoves(positionOf(a1 to white(PieceType.KING))).size)
    }

    @Test
    fun castlingCandidatesNeedRightsAndEmptyInterveningSquares() {
        val e1 = squareOf(4, 0)
        val placements =
            arrayOf(
                e1 to white(PieceType.KING),
                squareOf(0, 0) to white(PieceType.ROOK),
                squareOf(7, 0) to white(PieceType.ROOK),
            )
        val bothRights = CastlingRights(true, true, false, false)

        val withRights = pseudoLegalMoves(positionOf(*placements, castlingRights = bothRights))
        assertContains(withRights, Move(e1, squareOf(6, 0)))
        assertContains(withRights, Move(e1, squareOf(2, 0)))

        val withoutRights = pseudoLegalMoves(positionOf(*placements))
        assertFalse(Move(e1, squareOf(6, 0)) in withoutRights)
        assertFalse(Move(e1, squareOf(2, 0)) in withoutRights)

        val blocked =
            pseudoLegalMoves(
                positionOf(
                    *placements,
                    squareOf(5, 0) to white(PieceType.BISHOP),
                    squareOf(1, 0) to white(PieceType.KNIGHT),
                    castlingRights = bothRights,
                ),
            )
        assertFalse(Move(e1, squareOf(6, 0)) in blocked)
        assertFalse(Move(e1, squareOf(2, 0)) in blocked)
    }

    @Test
    fun blackCastlingCandidatesUseRankSeven() {
        val e8 = squareOf(4, 7)
        val moves =
            pseudoLegalMoves(
                positionOf(
                    e8 to black(PieceType.KING),
                    squareOf(0, 7) to black(PieceType.ROOK),
                    squareOf(7, 7) to black(PieceType.ROOK),
                    sideToMove = Color.BLACK,
                    castlingRights = CastlingRights(false, false, true, true),
                ),
            )
        assertContains(moves, Move(e8, squareOf(6, 7)))
        assertContains(moves, Move(e8, squareOf(2, 7)))
    }

    @Test
    fun pinnedRookStillGeneratesPseudoLegalMoves() {
        // White rook e2 is absolutely pinned by the rook on e8 — king safety is a later phase's
        // filter, so pseudo-legal generation must still emit its sideways moves.
        val e1 = squareOf(4, 0)
        val e2 = squareOf(4, 1)
        val e8 = squareOf(4, 7)
        val moves =
            pseudoLegalMoves(
                positionOf(
                    e1 to white(PieceType.KING),
                    e2 to white(PieceType.ROOK),
                    e8 to black(PieceType.ROOK),
                ),
            )
        assertContains(moves, Move(e2, squareOf(3, 1)))
        assertContains(moves, Move(e2, squareOf(0, 1)))
    }
}
