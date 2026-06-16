package org.rurbaniak.smartchessboard.data.board.emulator

// Scenario builders that turn realistic multi-event board sequences into one-liners on top of the
// EmulatedBoard driver primitives (lift / place). They are deliberately CHESS-AGNOSTIC: the caller
// supplies raw square indices (Square.kt convention, a1 = 0) and the helpers only sequence the
// primitives — they hold no notion of pieces, sides, legality, or whose turn it is, and they inherit
// the primitives' consistency guards (lift requires an occupied square, place requires an empty one),
// so a mis-sequenced script throws loudly instead of producing a stream a real board never could.
//
// Why ordering is a first-class parameter, not a fixed sequence: research (research.md, Finding 1)
// found that the lift/place order players physically produce VARIES — some lift the captured piece
// first, some lift their own piece first; castling is performed king-first, rook-first, or with both
// pieces in the air at once. A downstream move-resolver (S-06) must cope with all of them, so the
// helpers expose the order as an enum the test picks, rather than baking in one "canonical" ordering
// the real world does not honour.
//
// Helpers NEVER press a confirm button: pressing the button is the player's explicit "I'm done with
// this move" signal (§1.5), a separate action from manipulating pieces. Keeping it out of the helpers
// means a test must press the button itself, which mirrors how a real game and S-06 see the two as
// distinct events. Lives in commonTest alongside EmulatedBoard (test fixtures until S-06 wires a
// production consumer, at which point both promote unchanged to commonMain).

/** Which piece a player lifts first when capturing — both orderings occur in real play (research Finding 1). */
enum class CaptureOrder {
    /** Lift the captured piece off the board first, then move the capturing piece onto the now-empty square. */
    CAPTURED_FIRST,

    /** Lift the capturing piece first, then remove the captured piece, then land the capturing piece. */
    MOVER_FIRST,
}

/** How a player physically performs castling — all three orderings are observed at the sensor level. */
enum class CastleOrder {
    /** Complete the king's move (lift + place) before touching the rook. */
    KING_FIRST,

    /** Complete the rook's move before touching the king. */
    ROOK_FIRST,

    /** Both pieces leave the board before either lands — the messiest ordering a reed matrix sees. */
    INTERLEAVED,
}

/**
 * A non-capturing move: the piece on [from] is lifted and set down on the empty square [to].
 * `promotionPush` is the same shape (named separately only for test readability).
 */
suspend fun EmulatedBoard.quietMove(
    from: Int,
    to: Int,
) {
    lift(from)
    place(to)
}

/**
 * A capture: the capturing piece starts on [from] and ends on [target], which currently holds the
 * captured piece. Both pieces are off the board before the capturing piece lands (a real board cannot
 * show two pieces on one square), so the orderings differ only in which piece is lifted first ([order]).
 */
suspend fun EmulatedBoard.capture(
    from: Int,
    target: Int,
    order: CaptureOrder,
) {
    when (order) {
        CaptureOrder.CAPTURED_FIRST -> {
            lift(target)
            lift(from)
            place(target)
        }

        CaptureOrder.MOVER_FIRST -> {
            lift(from)
            lift(target)
            place(target)
        }
    }
}

/**
 * Castling as two piece moves: king [kingFrom] → [kingTo] and rook [rookFrom] → [rookTo]. [order]
 * selects king-first, rook-first, or both-in-the-air-at-once. Destination squares must be empty and
 * origins occupied, exactly as the primitives' guards require.
 */
suspend fun EmulatedBoard.castle(
    kingFrom: Int,
    kingTo: Int,
    rookFrom: Int,
    rookTo: Int,
    order: CastleOrder,
) {
    when (order) {
        CastleOrder.KING_FIRST -> {
            lift(kingFrom)
            place(kingTo)
            lift(rookFrom)
            place(rookTo)
        }

        CastleOrder.ROOK_FIRST -> {
            lift(rookFrom)
            place(rookTo)
            lift(kingFrom)
            place(kingTo)
        }

        CastleOrder.INTERLEAVED -> {
            lift(kingFrom)
            lift(rookFrom)
            place(kingTo)
            place(rookTo)
        }
    }
}

/**
 * En passant: the pawn on [from] ends on the empty square [to], while the captured pawn sits on
 * [capturedSquare] (a different square from [to] — the defining trait of en passant). [order] selects
 * which pawn is lifted first, like an ordinary [capture].
 */
suspend fun EmulatedBoard.enPassant(
    from: Int,
    to: Int,
    capturedSquare: Int,
    order: CaptureOrder,
) {
    when (order) {
        CaptureOrder.CAPTURED_FIRST -> {
            lift(capturedSquare)
            lift(from)
            place(to)
        }

        CaptureOrder.MOVER_FIRST -> {
            lift(from)
            lift(capturedSquare)
            place(to)
        }
    }
}

/**
 * A j'adoube / sensor-blip shape: a piece is lifted from [square] and set straight back down on the
 * same square. Identical at the wire level to a transient spurious reed reading, so it doubles as the
 * primitive a noise/blip script uses — distinguishing intent from noise is the host's job, not the board's.
 */
suspend fun EmulatedBoard.adjust(square: Int) {
    lift(square)
    place(square)
}

/**
 * A pawn push that promotes: board-side this is identical to a [quietMove] (the board has no concept of
 * promotion — the piece swap happens off-board and the player confirms with a button). Named separately
 * so a test reads as the game it models.
 */
suspend fun EmulatedBoard.promotionPush(
    from: Int,
    to: Int,
) = quietMove(from, to)
