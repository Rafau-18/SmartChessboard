package org.rurbaniak.smartchessboard.domain.board

import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.SQUARE_COUNT
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import kotlin.math.abs

// The heart of S-06: resolve a recorded lift/place stream into exactly one legal move. A reed-switch
// board reports only occupancy transitions (which squares were lifted from / placed onto), never
// pieces or moves — turning that into a chess move is the host's job. The project's hardest bet is
// that this is *unambiguous* from the full event stream: a bare before/after snapshot cannot tell a
// capture (its destination shows no net occupancy change) from a quiet move, but the lift event on
// the destination is the discriminator, and the lift on the mover's origin always reveals which
// piece moved. Castling (two pieces, possibly airborne at once) and en passant (the captured pawn
// is not on the landing square) are likewise only resolvable from the event stream.
//
// Pure domain, all targets: no IO, no coroutines, no board handle — it takes the confirmed position
// and the events since the last confirmation and returns a [Resolution], never mutating anything.

/**
 * Resolves the lift/place [events] recorded since the last confirmation into exactly one legal move
 * of [position], or a typed rejection.
 *
 * The observed signature (net-vacated squares, net-arrived squares, and squares that were lifted then
 * re-occupied) is matched against the footprint of every move in `legalMoves(position)`. Matching is
 * exact on the net-vacated and net-arrived sets — a j'adoube or transient reed blip lifts a piece and
 * sets it straight back, so it never changes those — and a capture destination need only appear among
 * the lifted-then-re-occupied squares, with any extras ignored as noise (the j'adoube tolerance).
 * Resolution is order-independent: the capture and castling orderings a player physically produces all
 * collapse to the same net signature.
 */
fun resolvePhysicalMove(
    position: Position,
    events: List<BoardEvent.SquareEvent>,
): Resolution {
    val observed = observe(position, events)
    val matches = legalMoves(position).filter { footprintOf(position, it).matches(observed) }
    // Promotion expands one pawn push/capture into four moves sharing from/to; group so the collapse
    // is one entry, and so a genuine multi-move match (different from/to) is told apart as ambiguous.
    val groups = matches.groupBy { it.from to it.to }
    return when {
        groups.isEmpty() -> {
            classifyNoMatch(observed)
        }

        groups.size > 1 -> {
            Resolution.Ambiguous
        }

        else -> {
            val (fromTo, moves) = groups.entries.single()
            if (moves.any { it.promoteTo != null }) {
                Resolution.NeedsPromotion(fromTo.first, fromTo.second)
            } else {
                Resolution.Resolved(moves.single())
            }
        }
    }
}

/** The net occupancy change a sequence produced, classified per square (Square.kt indices). */
private class Observed(
    /** Occupied before, empty after — the squares pieces left (a mover's origin, an en-passant victim). */
    val vacated: Set<Int>,
    /** Empty before, occupied after — the squares pieces arrived on (a quiet/castling destination). */
    val arrived: Set<Int>,
    /**
     * Lifted at least once yet still occupied at the end — a capture destination (the captured piece
     * was removed and the mover set down in its place) or a j'adoube on its own square. No net change.
     */
    val liftedReoccupied: Set<Int>,
)

private fun observe(
    position: Position,
    events: List<BoardEvent.SquareEvent>,
): Observed {
    val before = position.toOccupancy()
    var after = before
    val lifted = mutableSetOf<Int>()
    for (event in events) {
        val bit = 1L shl event.square
        when (event.type) {
            SquareEventType.LIFT -> {
                after = after and bit.inv()
                lifted += event.square
            }

            SquareEventType.PLACE -> {
                after = after or bit
            }
        }
    }
    val vacated = mutableSetOf<Int>()
    val arrived = mutableSetOf<Int>()
    val liftedReoccupied = mutableSetOf<Int>()
    for (square in 0 until SQUARE_COUNT) {
        val bit = 1L shl square
        val occupiedBefore = before and bit != 0L
        val occupiedAfter = after and bit != 0L
        when {
            occupiedBefore && !occupiedAfter -> vacated += square
            !occupiedBefore && occupiedAfter -> arrived += square
            occupiedAfter && square in lifted -> liftedReoccupied += square
        }
    }
    return Observed(vacated, arrived, liftedReoccupied)
}

/**
 * The occupancy changes a [Move] is expected to produce in [position]. Capture / castle / en-passant
 * are derived from the position with the same rules the engine already uses (`sanForMove`,
 * `parsePgn`, `applyMove`): castling is a king two-file move (rook squares follow from the side);
 * en passant is a pawn moving diagonally onto an empty square (the captured pawn sits beside it).
 *
 * SYNC: the castle rook-square and en-passant captured-square geometry in [footprintOf] is
 * hand-mirrored from [org.rurbaniak.smartchessboard.domain.chess.applyMove]. It is not reused
 * directly because `applyMove` returns a whole `Position` and the SAN/PGN derivations are `private`
 * and return strings/Booleans — none yields a square-set footprint. If that geometry ever changes in
 * `applyMove`, change [footprintOf] in lockstep or physical-move resolution silently breaks.
 */
private class Footprint(
    val vacated: Set<Int>,
    val arrived: Set<Int>,
    val captureDest: Set<Int>,
) {
    fun matches(observed: Observed): Boolean =
        vacated == observed.vacated &&
            arrived == observed.arrived &&
            // Subset, not equality: extra lifted-then-re-occupied squares are j'adoube / sensor noise.
            captureDest.all { it in observed.liftedReoccupied }
}

private fun footprintOf(
    position: Position,
    move: Move,
): Footprint {
    val from = move.from
    val to = move.to
    val piece = requireNotNull(position.pieceAt(from)) { "no piece on square $from" }

    val isCastle = piece.type == PieceType.KING && abs(fileOf(to) - fileOf(from)) == 2
    if (isCastle) {
        val rank = rankOf(from)
        val (rookFrom, rookTo) =
            if (fileOf(to) == 6) {
                squareOf(7, rank) to squareOf(5, rank)
            } else {
                squareOf(0, rank) to squareOf(3, rank)
            }
        return Footprint(vacated = setOf(from, rookFrom), arrived = setOf(to, rookTo), captureDest = emptySet())
    }

    // A pawn moving diagonally onto an empty square can only be en passant (mirrors applyMove).
    val isEnPassant =
        piece.type == PieceType.PAWN && position.pieceAt(to) == null && fileOf(from) != fileOf(to)
    if (isEnPassant) {
        // The captured pawn sits on the destination file at the mover's departure rank.
        val capturedSquare = squareOf(fileOf(to), rankOf(from))
        return Footprint(vacated = setOf(from, capturedSquare), arrived = setOf(to), captureDest = emptySet())
    }

    val isCapture = position.pieceAt(to) != null
    return if (isCapture) {
        // The destination keeps its occupancy (captured piece replaced by the mover) — its lift is the
        // discriminator a snapshot misses, so it is a capture destination, not a net arrival.
        Footprint(vacated = setOf(from), arrived = emptySet(), captureDest = setOf(to))
    } else {
        Footprint(vacated = setOf(from), arrived = setOf(to), captureDest = emptySet())
    }
}

/**
 * When no legal move matches, tell "still in progress" from "finished but illegal": a piece set down
 * on a new square ([Observed.arrived]) or a capture that landed ([Observed.liftedReoccupied] paired
 * with a vacated origin) is a completed-looking move that simply is not legal; anything else is a
 * piece still in hand, a lone lift, or pure noise — incomplete, not a rejection of an illegal move.
 */
private fun classifyNoMatch(observed: Observed): Resolution {
    val placedOnNewSquare = observed.arrived.isNotEmpty()
    val captureLanded = observed.liftedReoccupied.isNotEmpty() && observed.vacated.isNotEmpty()
    return if (placedOnNewSquare || captureLanded) Resolution.Illegal else Resolution.Incomplete
}
