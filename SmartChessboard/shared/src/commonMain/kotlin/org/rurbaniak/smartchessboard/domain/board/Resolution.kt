package org.rurbaniak.smartchessboard.domain.board

import org.rurbaniak.smartchessboard.domain.chess.Move

/**
 * The outcome of resolving a recorded lift/place sequence against a known position
 * ([resolvePhysicalMove]). Mirrors how the engine keeps its result types ([Move], `MoveOutcome`)
 * beside the functions that produce them.
 *
 * The resolver never fabricates a move: [Resolved] always carries a move taken straight from
 * `legalMoves`, and a sequence it cannot turn into exactly one legal move yields one of the typed
 * rejections instead of a guess.
 */
sealed interface Resolution {
    /** Exactly one legal move explains the observed occupancy changes. */
    data class Resolved(
        val move: Move,
    ) : Resolution

    /**
     * The observed changes uniquely identify a pawn reaching the last rank, but the promotion piece
     * is still unknown — the four promotion moves share this [from]/[to] (contract §1.5: the host
     * blocks acceptance of the next confirm until the player picks). The caller raises the picker and
     * re-resolves once the piece is chosen.
     */
    data class NeedsPromotion(
        val from: Int,
        val to: Int,
    ) : Resolution

    /**
     * More than one legal move matches. Defensive: under a full lift/place stream the mover's origin
     * lift and the capture-destination lift always disambiguate, so this is unreachable in practice
     * (no two distinct legal moves share a footprint). It exists so the resolver stays total rather
     * than ever returning a fabricated move.
     */
    data object Ambiguous : Resolution

    /** A completed-looking move — a piece set down on a new square, or a capture that landed — that is not legal. */
    data object Illegal : Resolution

    /** No resolvable move yet: nothing moved, only j'adoube/noise, or a piece is still in hand (lifted, not placed). */
    data object Incomplete : Resolution
}
