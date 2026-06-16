package org.rurbaniak.smartchessboard.domain.games

import org.rurbaniak.smartchessboard.domain.chess.Color
import org.rurbaniak.smartchessboard.domain.chess.GameStatus as ChessStatus

/**
 * The [GameResult] that closes a record reaching the terminal engine [status] with [sideToMove] to
 * move (FR-007): checkmate records the side that just delivered mate — i.e. [sideToMove].opposite —
 * as the winner; stalemate is a draw. A non-terminal status ([ChessStatus.Ongoing] /
 * [ChessStatus.Check]) yields null — there is nothing to record yet.
 *
 * This and the token helpers below are the pure mappings the S-05 game-closure path depends on;
 * keeping winner inference and token formatting out of the ViewModel makes them separately testable
 * on every target — Kotlin/Native resolves `when` differently from the JVM (lessons.md: a commonMain
 * mapping is not green until the Native suite passes). Layering: this couples `domain/games` to the
 * lower-level `domain/chess` engine, an acceptable games → chess direction.
 */
fun gameResultFor(
    status: ChessStatus,
    sideToMove: Color,
): GameResult? =
    when (status) {
        ChessStatus.Checkmate -> sideToMove.opposite.toGameResult()
        ChessStatus.Stalemate -> GameResult.DRAW
        ChessStatus.Ongoing, ChessStatus.Check -> null
    }

/**
 * The canonical PGN result token for this record state (contract §5.5): `WHITE → 1-0`,
 * `BLACK → 0-1`, `DRAW → 1/2-1/2`, and null (in progress) → `*`. The strings are kept identical to
 * the parser's `RESULT_TOKENS`, so a serialised result round-trips through [parsePgn].
 */
fun GameResult?.toPgnResultToken(): String =
    when (this) {
        GameResult.WHITE -> "1-0"
        GameResult.BLACK -> "0-1"
        GameResult.DRAW -> "1/2-1/2"
        null -> "*"
    }

/**
 * The inverse of [toPgnResultToken]: a canonical PGN token → [GameResult], with `*` (and any
 * unrecognised token) → null (in progress / unknown).
 */
fun gameResultFromPgnToken(token: String): GameResult? =
    when (token) {
        "1-0" -> GameResult.WHITE
        "0-1" -> GameResult.BLACK
        "1/2-1/2" -> GameResult.DRAW
        else -> null
    }

private fun Color.toGameResult(): GameResult =
    when (this) {
        Color.WHITE -> GameResult.WHITE
        Color.BLACK -> GameResult.BLACK
    }
