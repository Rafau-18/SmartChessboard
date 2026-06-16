package org.rurbaniak.smartchessboard.domain.games

import org.rurbaniak.smartchessboard.domain.chess.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.rurbaniak.smartchessboard.domain.chess.GameStatus as ChessStatus

class GameResultMappingTest {
    // --- (a) terminal engine state → GameResult ---

    @Test
    fun checkmateRecordsTheSideThatJustMovedAsWinner() {
        // Black to move and checkmated → White delivered mate → White wins.
        assertEquals(GameResult.WHITE, gameResultFor(ChessStatus.Checkmate, Color.BLACK))
        // White to move and checkmated → Black delivered mate → Black wins.
        assertEquals(GameResult.BLACK, gameResultFor(ChessStatus.Checkmate, Color.WHITE))
    }

    @Test
    fun stalemateIsADrawRegardlessOfSideToMove() {
        assertEquals(GameResult.DRAW, gameResultFor(ChessStatus.Stalemate, Color.WHITE))
        assertEquals(GameResult.DRAW, gameResultFor(ChessStatus.Stalemate, Color.BLACK))
    }

    @Test
    fun nonTerminalStatusYieldsNoResult() {
        assertNull(gameResultFor(ChessStatus.Ongoing, Color.WHITE))
        assertNull(gameResultFor(ChessStatus.Ongoing, Color.BLACK))
        assertNull(gameResultFor(ChessStatus.Check, Color.WHITE))
        assertNull(gameResultFor(ChessStatus.Check, Color.BLACK))
    }

    // --- (b) GameResult ↔ PGN token (both directions) ---

    @Test
    fun gameResultMapsToItsCanonicalPgnToken() {
        assertEquals("1-0", GameResult.WHITE.toPgnResultToken())
        assertEquals("0-1", GameResult.BLACK.toPgnResultToken())
        assertEquals("1/2-1/2", GameResult.DRAW.toPgnResultToken())
        val inProgress: GameResult? = null
        assertEquals("*", inProgress.toPgnResultToken())
    }

    @Test
    fun pgnTokenMapsBackToItsGameResult() {
        assertEquals(GameResult.WHITE, gameResultFromPgnToken("1-0"))
        assertEquals(GameResult.BLACK, gameResultFromPgnToken("0-1"))
        assertEquals(GameResult.DRAW, gameResultFromPgnToken("1/2-1/2"))
        assertNull(gameResultFromPgnToken("*"))
        assertNull(gameResultFromPgnToken("not-a-token"))
    }

    @Test
    fun resultTokenRoundTripsThroughBothMappings() {
        for (result in listOf(GameResult.WHITE, GameResult.BLACK, GameResult.DRAW)) {
            assertEquals(result, gameResultFromPgnToken(result.toPgnResultToken()))
        }
    }
}
