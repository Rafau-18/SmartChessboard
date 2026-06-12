package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.Position

/**
 * Parsed PGN tag pairs. Every tag is preserved in [tags] (insertion order); the fields the replay
 * UI displays are exposed as typed accessors over the same map, so nothing is lost or duplicated.
 */
data class PgnHeaders(
    val tags: Map<String, String> = emptyMap(),
) {
    val white: String? get() = tags["White"]
    val black: String? get() = tags["Black"]
    val result: String? get() = tags["Result"]
    val date: String? get() = tags["Date"]
}

/** Why parsing stopped at [PgnTruncation.plyIndex]. */
enum class PgnTruncationReason {
    /** The token resolved to zero legal moves — an illegal move or plain garbage. */
    UNRESOLVED_MOVE,

    /** The token matched more than one legal move (missing disambiguation). */
    AMBIGUOUS_MOVE,

    /** A `(` variation — unsupported by design; our own writer never produces variations. */
    UNSUPPORTED_VARIATION,
}

/**
 * Marks where parsing stopped. [plyIndex] is the 0-based index of the move that failed to
 * resolve — equal to `sanMoves.size` of the truncated [ReplayGame], so `positions[plyIndex]` is
 * the last valid position. Everything before the failure stays intact and replayable.
 */
data class PgnTruncation(
    val plyIndex: Int,
    val reason: PgnTruncationReason,
    val token: String,
)

/**
 * The in-memory model the replay UI consumes: positions derived from PGN at open time (contract
 * §5.4 — FEN is never stored per move), the SAN tokens that produced them, parsed headers, and an
 * optional truncation marker. Invariant: `positions.size == sanMoves.size + 1` — index 0 is the
 * start position and ply *n* is reached at `positions[n]`.
 */
data class ReplayGame(
    val headers: PgnHeaders,
    val sanMoves: List<String>,
    val positions: List<Position>,
    val truncation: PgnTruncation?,
) {
    init {
        require(positions.size == sanMoves.size + 1) {
            "positions (${positions.size}) must be exactly sanMoves (${sanMoves.size}) + 1"
        }
    }
}
