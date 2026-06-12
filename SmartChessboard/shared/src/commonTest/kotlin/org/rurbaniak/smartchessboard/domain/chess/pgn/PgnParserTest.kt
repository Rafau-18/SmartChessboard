package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.domain.chess.status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PgnParserTest {
    private fun square(name: String): Int = squareOf(name[0] - 'a', name[1] - '1')

    private fun ReplayGame.finalPieceAt(name: String): Piece? = positions.last().pieceAt(square(name))

    private fun assertComplete(game: ReplayGame) {
        assertNull(game.truncation, "expected a complete parse, got ${game.truncation}")
        assertEquals(game.sanMoves.size + 1, game.positions.size)
    }

    // --- Famous complete games (final position + move count) ---

    @Test
    fun operaGameReplaysToItsKnownMate() {
        val game = parsePgn(PgnFixtures.OPERA_GAME)
        assertComplete(game)
        assertEquals(33, game.sanMoves.size)
        assertEquals("Rd8#", game.sanMoves.last())
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), game.finalPieceAt("d8"))
        assertEquals(Piece(Color.BLACK, PieceType.KING), game.finalPieceAt("e8"))
        assertEquals(Color.BLACK, game.positions.last().sideToMove)
        assertEquals(GameStatus.Checkmate, status(game.positions.last()))
    }

    @Test
    fun immortalGameReplaysToItsKnownMate() {
        val game = parsePgn(PgnFixtures.IMMORTAL_GAME)
        assertComplete(game)
        assertEquals(45, game.sanMoves.size)
        assertEquals("Be7#", game.sanMoves.last())
        assertEquals(Piece(Color.WHITE, PieceType.BISHOP), game.finalPieceAt("e7"))
        assertEquals(Piece(Color.BLACK, PieceType.KING), game.finalPieceAt("d8"))
        assertEquals(GameStatus.Checkmate, status(game.positions.last()))
    }

    // --- Special moves ---

    @Test
    fun kingsideCastlingMovesKingAndRookForBothSides() {
        val game = parsePgn("1. e4 e5 2. Nf3 Nf6 3. Bc4 Bc5 4. O-O O-O")
        assertComplete(game)
        assertEquals(Piece(Color.WHITE, PieceType.KING), game.finalPieceAt("g1"))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), game.finalPieceAt("f1"))
        assertEquals(Piece(Color.BLACK, PieceType.KING), game.finalPieceAt("g8"))
        assertEquals(Piece(Color.BLACK, PieceType.ROOK), game.finalPieceAt("f8"))
    }

    @Test
    fun queensideCastlingMovesKingAndRookForBothSides() {
        val game = parsePgn("1. d4 d5 2. Nc3 Nc6 3. Bf4 Bf5 4. Qd2 Qd7 5. O-O-O O-O-O")
        assertComplete(game)
        assertEquals(Piece(Color.WHITE, PieceType.KING), game.finalPieceAt("c1"))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), game.finalPieceAt("d1"))
        assertEquals(Piece(Color.BLACK, PieceType.KING), game.finalPieceAt("c8"))
        assertEquals(Piece(Color.BLACK, PieceType.ROOK), game.finalPieceAt("d8"))
    }

    @Test
    fun enPassantCaptureRemovesTheBypassingPawn() {
        val game = parsePgn("1. e4 Nf6 2. e5 d5 3. exd6")
        assertComplete(game)
        assertEquals(5, game.sanMoves.size)
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), game.finalPieceAt("d6"))
        assertNull(game.finalPieceAt("d5"), "the en-passant-captured pawn must be gone")
        assertNull(game.finalPieceAt("e5"))
    }

    @Test
    fun promotionTokenProducesThePromotedPiece() {
        val game = parsePgn("1. e4 d5 2. exd5 c6 3. dxc6 e5 4. cxb7 e4 5. bxa8=Q")
        assertComplete(game)
        assertEquals(9, game.sanMoves.size)
        assertEquals(Piece(Color.WHITE, PieceType.QUEEN), game.finalPieceAt("a8"))
    }

    @Test
    fun fileDisambiguationPicksTheNamedKnight() {
        val game = parsePgn("1. d4 d5 2. Nf3 Nf6 3. Nbd2")
        assertComplete(game)
        assertEquals(Piece(Color.WHITE, PieceType.KNIGHT), game.finalPieceAt("d2"))
        assertNull(game.finalPieceAt("b1"), "the b1 knight is the one that moved")
        assertEquals(Piece(Color.WHITE, PieceType.KNIGHT), game.finalPieceAt("f3"))
    }

    @Test
    fun checkAndMateSuffixesResolveAndStayInSanMoves() {
        val game = parsePgn("1. f3 e5 2. g4 Qh4#")
        assertComplete(game)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), game.sanMoves)
        assertEquals(GameStatus.Checkmate, status(game.positions.last()))
    }

    // --- Headers ---

    @Test
    fun headersAreParsedAndSurfaced() {
        val game = parsePgn(PgnFixtures.OPERA_GAME)
        assertEquals("Paul Morphy", game.headers.white)
        assertEquals("Duke Karl / Count Isouard", game.headers.black)
        assertEquals("1-0", game.headers.result)
        assertEquals("1858.11.02", game.headers.date)
        assertEquals("Paris Opera", game.headers.tags["Event"])
    }

    @Test
    fun headersOnlyInputYieldsASinglePositionGame() {
        val game = parsePgn("[White \"Alice\"]\n[Black \"Bob\"]\n\n*")
        assertComplete(game)
        assertTrue(game.sanMoves.isEmpty())
        assertEquals(listOf(Position.start()), game.positions)
        assertEquals("Alice", game.headers.white)
    }

    @Test
    fun emptyStringYieldsASinglePositionGame() {
        val game = parsePgn("")
        assertComplete(game)
        assertTrue(game.sanMoves.isEmpty())
        assertEquals(listOf(Position.start()), game.positions)
        assertNull(game.headers.white)
    }

    // --- Movetext syntax tolerance ---

    @Test
    fun commentsNagsAndContinuationDotsAreSkipped() {
        val game = parsePgn("1. f3 {dubious} e5 $2 2. g4 2... Qh4#")
        assertComplete(game)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), game.sanMoves)
    }

    @Test
    fun glueDigitsAndAnnotationSuffixesAreTolerated() {
        val game = parsePgn("1.f3 e5 2.g4?? Qh4#")
        assertComplete(game)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), game.sanMoves)
    }

    @Test
    fun resultTokenEndsMovetext() {
        val game = parsePgn("1. e4 e5 1/2-1/2 ignored garbage")
        assertComplete(game)
        assertEquals(listOf("e4", "e5"), game.sanMoves)
    }

    // --- In-progress documents (S-04 write shapes) ---

    @Test
    fun inProgressDocumentWithOddPlyCountParses() {
        val game = parsePgn("[Result \"*\"]\n[Mode \"digital\"]\n\n1. e4 e5 2. Nf3 *")
        assertComplete(game)
        assertEquals(listOf("e4", "e5", "Nf3"), game.sanMoves)
        assertEquals("*", game.headers.result)
        assertEquals("digital", game.headers.tags["Mode"])
    }

    @Test
    fun inProgressDocumentWithEmptyMovetextParses() {
        val game = parsePgn("[Result \"*\"]\n\n*")
        assertComplete(game)
        assertTrue(game.sanMoves.isEmpty())
        assertEquals(listOf(Position.start()), game.positions)
        assertEquals("*", game.headers.result)
    }

    // --- Truncation semantics (replay-up-to-error) ---

    @Test
    fun illegalMoveMidGameTruncatesAtThatPlyWithPriorPositionsIntact() {
        val game = parsePgn("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf3")
        assertEquals(PgnTruncationReason.UNRESOLVED_MOVE, game.truncation?.reason)
        assertEquals(7, game.truncation?.plyIndex)
        assertEquals(7, game.sanMoves.size)
        assertEquals(8, game.positions.size)
        assertEquals(Piece(Color.WHITE, PieceType.BISHOP), game.finalPieceAt("a4"))
    }

    @Test
    fun garbageTokenTruncatesInsteadOfThrowing() {
        val game = parsePgn("1. e4 banana e5")
        assertEquals(PgnTruncationReason.UNRESOLVED_MOVE, game.truncation?.reason)
        assertEquals(1, game.truncation?.plyIndex)
        assertEquals("banana", game.truncation?.token)
        assertEquals(listOf("e4"), game.sanMoves)
    }

    @Test
    fun pureGarbageInputTruncatesAtPlyZero() {
        val game = parsePgn("lorem ipsum dolor")
        assertEquals(PgnTruncationReason.UNRESOLVED_MOVE, game.truncation?.reason)
        assertEquals(0, game.truncation?.plyIndex)
        assertEquals(listOf(Position.start()), game.positions)
    }

    @Test
    fun ambiguousTokenTruncates() {
        // After 1. d4 d5 2. Nf3 Nf6, both white knights can reach d2 — bare "Nd2" is ambiguous.
        val game = parsePgn("1. d4 d5 2. Nf3 Nf6 3. Nd2")
        assertEquals(PgnTruncationReason.AMBIGUOUS_MOVE, game.truncation?.reason)
        assertEquals(4, game.truncation?.plyIndex)
        assertEquals(4, game.sanMoves.size)
    }

    @Test
    fun variationOpenerTruncates() {
        val game = parsePgn("1. e4 e5 (1... c5) 2. Nf3")
        assertEquals(PgnTruncationReason.UNSUPPORTED_VARIATION, game.truncation?.reason)
        assertEquals(2, game.truncation?.plyIndex)
        assertEquals(listOf("e4", "e5"), game.sanMoves)
    }

    @Test
    fun incompletePromotionTokenTruncates() {
        // The engine never auto-promotes; a bare pawn push to the last rank resolves to nothing.
        val game = parsePgn("1. e4 d5 2. exd5 c6 3. dxc6 e5 4. cxb7 e4 5. bxa8")
        assertEquals(PgnTruncationReason.UNRESOLVED_MOVE, game.truncation?.reason)
        assertEquals(8, game.truncation?.plyIndex)
    }
}
