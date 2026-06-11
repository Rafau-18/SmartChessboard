package org.rurbaniak.smartchessboard.domain.chess

// Square indexing convention, fixed by the BLE contract (docs/reference/contract-surfaces.md §1.3):
// index = file + 8 * rank, with file a–h = 0–7 and rank 1–8 = 0–7, so a1 = 0, h1 = 7, a8 = 56, h8 = 63.
// This file is the single authority for the (file, rank) ↔ index arithmetic; no other file may
// open-code it. Consequences: white pawns advance +8 and black pawns -8; white's back rank is
// rank 0 (indices 0–7) and black's is rank 7 (indices 56–63).

const val SQUARE_COUNT = 64

/** Index of the square at ([file], [rank]); both must be in 0..7. */
fun squareOf(
    file: Int,
    rank: Int,
): Int {
    require(file in 0..7) { "file must be in 0..7, was $file" }
    require(rank in 0..7) { "rank must be in 0..7, was $rank" }
    return file + 8 * rank
}

/** File 0..7 (a–h) of [square]. */
fun fileOf(square: Int): Int = square % 8

/** Rank 0..7 (1–8) of [square]. */
fun rankOf(square: Int): Int = square / 8

fun isValidSquare(square: Int): Boolean = square in 0 until SQUARE_COUNT

/**
 * The square one ([fileDelta], [rankDelta]) step away from [square], or null when the step leaves
 * the board. Stepping in (file, rank) space — not by raw index offset — is what makes ray walks
 * stop at the board edge instead of wrapping between files.
 */
fun offsetOrNull(
    square: Int,
    fileDelta: Int,
    rankDelta: Int,
): Int? {
    val file = fileOf(square) + fileDelta
    val rank = rankOf(square) + rankDelta
    return if (file in 0..7 && rank in 0..7) squareOf(file, rank) else null
}
