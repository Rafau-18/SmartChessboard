package org.rurbaniak.smartchessboard.domain.chess.pgn

/**
 * Header values for one serialized PGN document (contract §5.2). [date] is already `YYYY.MM.DD` —
 * the caller derives it from the record's ISO-8601 `created_at` by reformatting; this layer adds
 * no datetime dependency. [result] doubles as the movetext termination marker (always `"*"` while
 * the game is in progress, §5.5).
 */
data class PgnMeta(
    val event: String,
    val date: String,
    val white: String,
    val black: String,
    val result: String,
    val mode: String,
)

/**
 * Serializes [meta] + [sanMoves] into the canonical PGN document stored in `games.pgn`: the §5.2
 * tag header (Event, Date, White, Black, Result, Mode), a blank line, then movetext with move
 * numbers and the termination marker equal to [PgnMeta.result]. The inverse of [parsePgn]:
 * `parsePgn(writePgn(meta, sans))` reproduces the same moves and positions, including the
 * in-progress shapes (`[Result "*"]`, odd ply counts, empty movetext).
 */
fun writePgn(
    meta: PgnMeta,
    sanMoves: List<String>,
): String =
    buildString {
        appendTag("Event", meta.event)
        appendTag("Date", meta.date)
        appendTag("White", meta.white)
        appendTag("Black", meta.black)
        appendTag("Result", meta.result)
        appendTag("Mode", meta.mode)
        append('\n')
        for ((ply, san) in sanMoves.withIndex()) {
            if (ply % 2 == 0) {
                if (ply > 0) append(' ')
                append(ply / 2 + 1)
                append('.')
            }
            append(' ')
            append(san)
        }
        if (sanMoves.isNotEmpty()) append(' ')
        append(meta.result)
        append('\n')
    }

/** One `[Name "value"]` line; `"` and `\` in the value are escaped per the parser's rules. */
private fun StringBuilder.appendTag(
    name: String,
    value: String,
) {
    append('[')
    append(name)
    append(" \"")
    for (char in value) {
        if (char == '"' || char == '\\') append('\\')
        append(char)
    }
    append("\"]\n")
}
