package org.rurbaniak.smartchessboard.domain.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

/** Same reference as PerftTest — castling, en passant, and promotion squares all in play. */
private const val KIWIPETE_FEN = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"

class FenTest {
    // --- exact emission ---

    @Test
    fun startPositionEmitsTheCanonicalFen() {
        assertEquals(START_FEN, Position.start().toFen())
    }

    @Test
    fun kiwipeteSerializesBackToItsCanonicalString() {
        assertEquals("$KIWIPETE_FEN 0 1", fen(KIWIPETE_FEN).toFen())
    }

    @Test
    fun halfmoveAndFullmoveCountersSerializeAsStored() {
        // 1.e4 e5 2.Nf3 — one reversible move since the last pawn push; Black is about to move in turn 2.
        val position =
            listOf(
                Move(squareOf(4, 1), squareOf(4, 3)),
                Move(squareOf(4, 6), squareOf(4, 4)),
                Move(squareOf(6, 0), squareOf(5, 2)),
            ).fold(Position.start(), ::applyMove)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", position.toFen())
    }

    // --- castling field ---

    @Test
    fun castlingRightsSubsetRoundTripsExactly() {
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 3 17", fen("r3k2r/8/8/8/8/8/8/R3K2R w Kq - 3 17").toFen())
    }

    @Test
    fun lostCastlingRightsEmitDash() {
        assertEquals("r3k2r/8/8/8/8/8/8/R3K2R b - - 0 40", fen("r3k2r/8/8/8/8/8/8/R3K2R b - - 0 40").toFen())
    }

    // --- en passant capturability rule ---

    @Test
    fun nonCapturableEnPassantTargetIsSuppressed() {
        val afterE4 = applyMove(Position.start(), Move(squareOf(4, 1), squareOf(4, 3)))
        // applyMove records e3 after every double push; no black pawn can capture onto it, so the
        // field must read "-" (strict providers reject "e3" here).
        assertNotNull(afterE4.enPassantTarget)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", afterE4.toFen())
    }

    @Test
    fun nonCapturableEnPassantTargetIsSuppressedForBlackDoublePushToo() {
        val position =
            listOf(
                Move(squareOf(4, 1), squareOf(4, 3)),
                Move(squareOf(4, 6), squareOf(4, 4)),
            ).fold(Position.start(), ::applyMove)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2", position.toFen())
    }

    @Test
    fun capturableEnPassantTargetIsEmittedForWhite() {
        // 1.e4 c5 2.e5 d5 — the e5 pawn may capture onto d6 en passant.
        val position =
            listOf(
                Move(squareOf(4, 1), squareOf(4, 3)),
                Move(squareOf(2, 6), squareOf(2, 4)),
                Move(squareOf(4, 3), squareOf(4, 4)),
                Move(squareOf(3, 6), squareOf(3, 4)),
            ).fold(Position.start(), ::applyMove)
        assertEquals("rnbqkbnr/pp2pppp/8/2ppP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3", position.toFen())
    }

    @Test
    fun capturableEnPassantTargetIsEmittedForBlack() {
        // 1.e4 c5 2.Nf3 c4 3.d4 — the c4 pawn may capture onto d3 en passant.
        val position =
            listOf(
                Move(squareOf(4, 1), squareOf(4, 3)),
                Move(squareOf(2, 6), squareOf(2, 4)),
                Move(squareOf(6, 0), squareOf(5, 2)),
                Move(squareOf(2, 4), squareOf(2, 3)),
                Move(squareOf(3, 1), squareOf(3, 3)),
            ).fold(Position.start(), ::applyMove)
        assertEquals("rnbqkbnr/pp1ppppp/8/8/2pPP3/5N2/PPP2PPP/RNBQKB1R b KQkq d3 0 3", position.toFen())
    }

    // --- promotion-heavy placement ---

    @Test
    fun promotionHeavyPositionRoundTripsExactly() {
        val text = "QQ4k1/8/8/8/8/8/qq6/6K1 w - - 0 60"
        assertEquals(text, fen(text).toFen())
    }

    // --- round-trip against the test-only parser ---

    @Test
    fun perftReferencePositionsRoundTripThroughToFen() {
        for (text in listOf(START_FEN, KIWIPETE_FEN)) {
            val position = fen(text)
            assertEquals(position, fen(position.toFen()), "round-trip failed for: $text")
        }
    }
}
