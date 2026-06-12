package org.rurbaniak.smartchessboard.domain.chess.pgn

import org.rurbaniak.smartchessboard.domain.chess.Move
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.applyMove
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.legalMoves
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import org.rurbaniak.smartchessboard.domain.chess.squareOf

// Deliberately regex-free: SAN tokens and tag lines are decomposed by hand because Kotlin/Native's
// regex engine resolves optional-group backtracking differently from the JVM (the same pattern
// matched on androidHostTest and failed on iosSimulatorArm64). Manual parsing is deterministic on
// every target — and the grammar is small enough that it stays readable.

private val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "*")

/**
 * Parses one PGN game into a [ReplayGame], resolving each SAN token by filtering [legalMoves] of
 * the current position — the engine is the single source of legality; there is no SAN grammar
 * beyond token shape. Never throws on game content: a token that resolves to zero or multiple
 * legal moves, or an unsupported `(` variation, stops parsing with a [PgnTruncation] while keeping
 * every position up to the failure. Empty or headers-only input is a valid single-position game
 * (`games.pgn` defaults to `''` and in-progress records may end mid-pair).
 */
fun parsePgn(pgn: String): ReplayGame {
    val lines = pgn.lines()
    val tags = LinkedHashMap<String, String>()
    var lineIndex = 0
    while (lineIndex < lines.size) {
        val line = lines[lineIndex].trim()
        if (line.isEmpty()) {
            lineIndex++
            continue
        }
        val tag = parseTagLine(line) ?: break
        tags[tag.first] = tag.second
        lineIndex++
    }

    val sanMoves = mutableListOf<String>()
    val positions = mutableListOf(Position.start())
    var truncation: PgnTruncation? = null

    for (token in tokenize(lines.drop(lineIndex).joinToString("\n"))) {
        // Result tokens must be checked before number stripping — "1-0" starts with a digit.
        if (token in RESULT_TOKENS) break
        if (token.startsWith("$")) continue
        if (token == "(" || token == ")") {
            truncation = PgnTruncation(sanMoves.size, PgnTruncationReason.UNSUPPORTED_VARIATION, token)
            break
        }
        val san = stripMoveNumber(token) ?: continue
        // Annotations ("!?") are dropped from the stored token; check/mate suffixes are canonical
        // SAN and stay for display, but resolution matches on the bare move core.
        val display = san.trimEnd('!', '?')
        val core = display.trimEnd('+', '#')
        if (core.isEmpty()) continue
        val position = positions.last()
        val resolved = resolveSan(core, position)
        when (resolved.size) {
            1 -> {
                sanMoves += display
                positions += applyMove(position, resolved.single())
            }

            0 -> {
                truncation = PgnTruncation(sanMoves.size, PgnTruncationReason.UNRESOLVED_MOVE, token)
                break
            }

            else -> {
                truncation = PgnTruncation(sanMoves.size, PgnTruncationReason.AMBIGUOUS_MOVE, token)
                break
            }
        }
    }
    return ReplayGame(PgnHeaders(tags), sanMoves, positions, truncation)
}

/** Parses a `[Tag "value"]` line (with `\"`/`\\` escapes in the value), or null when not one. */
private fun parseTagLine(line: String): Pair<String, String>? {
    if (!line.startsWith("[") || !line.endsWith("]")) return null
    val inner = line.substring(1, line.length - 1).trim()
    val nameEnd = inner.indexOfFirst { it.isWhitespace() }
    if (nameEnd <= 0) return null
    val name = inner.substring(0, nameEnd)
    if (!name.all { it.isLetterOrDigit() || it == '_' }) return null
    val quoted = inner.substring(nameEnd).trim()
    if (quoted.length < 2 || !quoted.startsWith("\"") || !quoted.endsWith("\"")) return null
    val raw = quoted.substring(1, quoted.length - 1)
    val value = StringBuilder()
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '\\' && i + 1 < raw.length && (raw[i + 1] == '"' || raw[i + 1] == '\\')) {
            value.append(raw[i + 1])
            i += 2
        } else {
            value.append(raw[i])
            i++
        }
    }
    return name to value.toString()
}

/**
 * Splits movetext on whitespace, drops `{…}` comments (an unclosed comment swallows the rest),
 * and emits `(`/`)` as standalone tokens even when glued to a move.
 */
private fun tokenize(movetext: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inComment = false

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
        }
    }
    for (char in movetext) {
        when {
            inComment -> {
                if (char == '}') inComment = false
            }

            char == '{' -> {
                flush()
                inComment = true
            }

            char == '(' || char == ')' -> {
                flush()
                tokens += char.toString()
            }

            char.isWhitespace() -> {
                flush()
            }

            else -> {
                current.append(char)
            }
        }
    }
    flush()
    return tokens
}

/**
 * Drops a leading move-number prefix — `12.`, `3...`, or glued `1.e4` — and standalone ellipses.
 * Returns null when nothing but the prefix remains (the token carried no move).
 */
private fun stripMoveNumber(token: String): String? {
    if (token.all { it == '.' }) return null
    if (!token.first().isDigit()) return token
    return token.dropWhile { it.isDigit() }.dropWhile { it == '.' }.ifEmpty { null }
}

/** The decomposed shape of a SAN move token; legality is the engine's job, not this struct's. */
private data class SanShape(
    val pieceType: PieceType,
    val targetSquare: Int,
    val disambiguationFile: Int?,
    val disambiguationRank: Int?,
    val isCapture: Boolean,
    val promotion: PieceType?,
)

/**
 * Every legal move matching [san]: piece type, target square, optional file/rank disambiguation,
 * capture marker, and promotion piece all filter the engine's legal set. Castling (`O-O`/`O-O-O`,
 * zeros accepted) maps to the king's two-file move. A non-SAN-shaped token matches nothing.
 */
private fun resolveSan(
    san: String,
    position: Position,
): List<Move> {
    val legal = legalMoves(position)
    val castle = san.replace('0', 'O')
    if (castle == "O-O" || castle == "O-O-O") {
        val fileDelta = if (castle == "O-O") 2 else -2
        return legal.filter { move ->
            position.pieceAt(move.from)?.type == PieceType.KING &&
                fileOf(move.to) - fileOf(move.from) == fileDelta
        }
    }
    val shape = parseSanShape(san) ?: return emptyList()
    return legal.filter { move ->
        val piece = position.pieceAt(move.from) ?: return@filter false
        piece.type == shape.pieceType &&
            move.to == shape.targetSquare &&
            (shape.disambiguationFile == null || fileOf(move.from) == shape.disambiguationFile) &&
            (shape.disambiguationRank == null || rankOf(move.from) == shape.disambiguationRank) &&
            move.promoteTo == shape.promotion &&
            isCapture(position, move) == shape.isCapture
    }
}

/**
 * Decomposes a bare SAN core (no castling, no suffixes) from the end: optional `=X` promotion,
 * then the mandatory target square, then the leading piece letter, capture `x`, and whatever sits
 * between as file/rank disambiguation. Returns null for anything that is not SAN-shaped.
 */
private fun parseSanShape(san: String): SanShape? {
    var rest = san
    var promotion: PieceType? = null
    if (rest.length >= 2 && rest[rest.length - 2] == '=') {
        promotion = promotionTypeOrNull(rest.last()) ?: return null
        rest = rest.dropLast(2)
    }
    if (rest.length < 2) return null
    val fileChar = rest[rest.length - 2]
    val rankChar = rest[rest.length - 1]
    if (fileChar !in 'a'..'h' || rankChar !in '1'..'8') return null
    val targetSquare = squareOf(fileChar - 'a', rankChar - '1')
    rest = rest.dropLast(2)

    var pieceType = PieceType.PAWN
    if (rest.isNotEmpty() && rest.first() in "KQRBN") {
        pieceType = pieceTypeOf(rest.first())
        rest = rest.drop(1)
    }
    var isCapture = false
    if (rest.isNotEmpty() && rest.last() == 'x') {
        isCapture = true
        rest = rest.dropLast(1)
    }
    var disambiguationFile: Int? = null
    var disambiguationRank: Int? = null
    when (rest.length) {
        0 -> {}

        1 -> {
            when (rest[0]) {
                in 'a'..'h' -> disambiguationFile = rest[0] - 'a'
                in '1'..'8' -> disambiguationRank = rest[0] - '1'
                else -> return null
            }
        }

        2 -> {
            if (rest[0] !in 'a'..'h' || rest[1] !in '1'..'8') return null
            disambiguationFile = rest[0] - 'a'
            disambiguationRank = rest[1] - '1'
        }

        else -> {
            return null
        }
    }
    return SanShape(pieceType, targetSquare, disambiguationFile, disambiguationRank, isCapture, promotion)
}

/** A move is a capture when the target is occupied or it is a pawn's diagonal en-passant take. */
private fun isCapture(
    position: Position,
    move: Move,
): Boolean =
    position.pieceAt(move.to) != null ||
        (
            position.pieceAt(move.from)?.type == PieceType.PAWN &&
                move.to == position.enPassantTarget &&
                fileOf(move.from) != fileOf(move.to)
        )

private fun pieceTypeOf(letter: Char): PieceType =
    when (letter) {
        'K' -> PieceType.KING
        'Q' -> PieceType.QUEEN
        'R' -> PieceType.ROOK
        'B' -> PieceType.BISHOP
        'N' -> PieceType.KNIGHT
        else -> PieceType.PAWN
    }

/** Promotion admits only the four target pieces — `=K` is not SAN. */
private fun promotionTypeOrNull(letter: Char): PieceType? =
    when (letter) {
        'Q' -> PieceType.QUEEN
        'R' -> PieceType.ROOK
        'B' -> PieceType.BISHOP
        'N' -> PieceType.KNIGHT
        else -> null
    }
