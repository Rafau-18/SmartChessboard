package org.rurbaniak.smartchessboard.domain.chess

/** The four castling-right flags (FEN "KQkq"). Rights are only ever revoked, never restored. */
data class CastlingRights(
    val whiteKingSide: Boolean,
    val whiteQueenSide: Boolean,
    val blackKingSide: Boolean,
    val blackQueenSide: Boolean,
) {
    companion object {
        val ALL = CastlingRights(true, true, true, true)
    }
}

/**
 * Immutable board state carrying every field an exact FEN needs (FEN serialization itself is a
 * later slice's job). State transitions come only from applying a move, which returns a new
 * [Position] and leaves this one untouched.
 */
data class Position(
    /** 64 entries indexed per the convention in Square.kt (a1 = 0, h8 = 63); null = empty. */
    val board: List<Piece?>,
    val sideToMove: Color,
    val castlingRights: CastlingRights,
    /**
     * The square a pawn could capture onto en passant. Set only by a double pawn push and valid
     * for exactly one ply — every other move clears it.
     */
    val enPassantTarget: Int?,
    /** Plies since the last pawn move or capture. Maintained for FEN; never claimed as a draw (FR-018). */
    val halfmoveClock: Int,
    /** Starts at 1, incremented after Black moves. */
    val fullmoveNumber: Int,
) {
    init {
        require(board.size == SQUARE_COUNT) { "board must have $SQUARE_COUNT entries, had ${board.size}" }
        require(enPassantTarget == null || isValidSquare(enPassantTarget)) {
            "enPassantTarget must be a valid square index, was $enPassantTarget"
        }
    }

    fun pieceAt(square: Int): Piece? = board[square]

    companion object {
        private val BACK_RANK =
            listOf(
                PieceType.ROOK,
                PieceType.KNIGHT,
                PieceType.BISHOP,
                PieceType.QUEEN,
                PieceType.KING,
                PieceType.BISHOP,
                PieceType.KNIGHT,
                PieceType.ROOK,
            )

        /** The standard chess starting position. */
        fun start(): Position {
            val board = MutableList<Piece?>(SQUARE_COUNT) { null }
            for (file in 0..7) {
                board[squareOf(file, 0)] = Piece(Color.WHITE, BACK_RANK[file])
                board[squareOf(file, 1)] = Piece(Color.WHITE, PieceType.PAWN)
                board[squareOf(file, 6)] = Piece(Color.BLACK, PieceType.PAWN)
                board[squareOf(file, 7)] = Piece(Color.BLACK, BACK_RANK[file])
            }
            return Position(
                board = board,
                sideToMove = Color.WHITE,
                castlingRights = CastlingRights.ALL,
                enPassantTarget = null,
                halfmoveClock = 0,
                fullmoveNumber = 1,
            )
        }
    }
}
