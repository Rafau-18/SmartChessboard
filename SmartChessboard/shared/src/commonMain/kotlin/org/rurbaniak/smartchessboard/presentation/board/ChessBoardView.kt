package org.rurbaniak.smartchessboard.presentation.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.rurbaniak.smartchessboard.domain.chess.Piece
import org.rurbaniak.smartchessboard.domain.chess.PieceType
import org.rurbaniak.smartchessboard.domain.chess.Position
import org.rurbaniak.smartchessboard.domain.chess.fileOf
import org.rurbaniak.smartchessboard.domain.chess.rankOf
import org.rurbaniak.smartchessboard.domain.chess.squareOf
import org.rurbaniak.smartchessboard.presentation.theme.ChessColors
import org.rurbaniak.smartchessboard.presentation.theme.LocalChessColors
import smartchessboard.shared.generated.resources.Res
import smartchessboard.shared.generated.resources.piece_bb
import smartchessboard.shared.generated.resources.piece_bk
import smartchessboard.shared.generated.resources.piece_bn
import smartchessboard.shared.generated.resources.piece_bp
import smartchessboard.shared.generated.resources.piece_bq
import smartchessboard.shared.generated.resources.piece_br
import smartchessboard.shared.generated.resources.piece_wb
import smartchessboard.shared.generated.resources.piece_wk
import smartchessboard.shared.generated.resources.piece_wn
import smartchessboard.shared.generated.resources.piece_wp
import smartchessboard.shared.generated.resources.piece_wq
import smartchessboard.shared.generated.resources.piece_wr
import kotlin.math.roundToInt
import org.rurbaniak.smartchessboard.domain.chess.Color as PieceColor

/**
 * Duration of the piece-slide animation, in milliseconds — the single knob for its speed: larger =
 * slower, smaller = snappier. The slide uses a plain [tween] with a smooth decelerate easing
 * ([FastOutSlowInEasing]), NOT a spring, so the piece travels straight to its destination with no
 * overshoot/bounce. To retune, change only this number (e.g. 250 for snappier, 400 for slower).
 */
private const val PIECE_SLIDE_DURATION_MS = 300

/** A from→to square pair (Square.kt indexing) drawn as an arrow above the pieces. */
data class BoardArrow(
    val from: Int,
    val to: Int,
)

/**
 * Optional play-mode wiring for [ChessBoardView]. When non-null the board becomes tappable and
 * renders selection + legal-target highlights; when null the board is display-only (Replay
 * renders exactly as before). [targetSquares] are the legal destinations of [selectedSquare] in
 * Square.kt indexing — empty targets get a dot, occupied (capture) targets a ring.
 */
data class BoardInteraction(
    val selectedSquare: Int?,
    val targetSquares: Set<Int>,
    val onSquareTap: (Int) -> Unit,
)

/**
 * Square pair of a UCI move ("e2e4"; promotion "e7e8q" parses with the suffix ignored), or null
 * when the string is not a well-formed UCI move.
 */
fun parseUciArrow(uci: String): BoardArrow? {
    if (uci.length < 4) return null
    val from = squareOrNull(uci[0], uci[1]) ?: return null
    val to = squareOrNull(uci[2], uci[3]) ?: return null
    return BoardArrow(from = from, to = to)
}

private fun squareOrNull(
    fileChar: Char,
    rankChar: Char,
): Int? {
    val file = fileChar - 'a'
    val rank = rankChar - '1'
    return if (file in 0..7 && rank in 0..7) squareOf(file, rank) else null
}

/**
 * Stateless 8×8 board rendering a [position] — it holds no game/navigation state; the host owns
 * selection and turn logic. [orientation] is the color rendered at the bottom (default white).
 * Passing a non-null [interaction] turns the board into a tappable play surface with selection +
 * legal-target highlights; leaving it null keeps the board display-only, so Replay call sites
 * render exactly as before. The board fills whatever box the caller's [modifier] provides, kept
 * square via `aspectRatio(1f)` — no hardcoded sizes, so the reuse contract stays size-agnostic.
 * [bestMoveArrow] is a render-only overlay (analysis); existing call sites are unaffected by its
 * default. [highlightedSquares] are tinted display-only (physical mode highlights lifted pieces) —
 * independent of [interaction], so a board can highlight without becoming tappable; empty by default,
 * so Replay/Play render exactly as before. [occupancyDots] is an optional live reed-matrix bitfield
 * (physical mode, S-09): a small neutral corner dot marks each sensed-occupied square, read h8-safe;
 * null by default, so every non-physical call site (Replay / Play / web) renders exactly as before.
 */
@Composable
fun ChessBoardView(
    position: Position,
    modifier: Modifier = Modifier,
    orientation: PieceColor = PieceColor.WHITE,
    interaction: BoardInteraction? = null,
    bestMoveArrow: BoardArrow? = null,
    highlightedSquares: Set<Int> = emptySet(),
    occupancyDots: Long? = null,
) {
    val chess = LocalChessColors.current

    // Piece-slide animation: when [position] advances by exactly one resolvable move, slide the moved
    // piece(s) from→to over the static grid; any other delta (a multi-ply jump, a load, an unresolvable
    // change) renders instantly. The slide is display-only — it never touches selection/tap handling.
    //
    // The diff and the destination suppression are derived SYNCHRONOUSLY during composition (not in a
    // LaunchedEffect), so the destination square is already suppressed in the *first* frame the new
    // position renders. Deferring this to an effect drew one frame with the piece already at its
    // destination before the overlay took over — a visible flash/teleport on Android & web (iOS folded
    // the effect into the same frame, so it looked smooth). Each move gets a fresh Animatable via
    // remember(slideKey), guaranteeing the overlay starts at the source (progress 0) rather than a
    // stale 1f left by the previous slide. prevPosition advances synchronously too, so rapid stepping
    // animates each single step instead of collapsing into a multi-ply (unresolvable) diff.
    var boardSizePx by remember { mutableStateOf(0) }
    var prevPosition by remember { mutableStateOf(position) }
    var slide by remember { mutableStateOf<BoardMoveAnimation?>(null) }
    var slideKey by remember { mutableStateOf(0) }
    if (position !== prevPosition) {
        val resolved = diffSingleMove(prevPosition, position)
        prevPosition = position
        slide = resolved
        if (resolved != null) slideKey++
    }
    val slideProgress = remember(slideKey) { Animatable(0f) }
    val slideSpec = tween<Float>(durationMillis = PIECE_SLIDE_DURATION_MS, easing = FastOutSlowInEasing)
    LaunchedEffect(slideKey) {
        if (slide != null) {
            slideProgress.animateTo(1f, slideSpec)
            slide = null
        }
    }
    // Destination square(s) of the moving piece(s) are suppressed in the static grid while the slide
    // runs, so the grid and the overlay never draw the same piece twice; the grid reveals the final
    // piece (incl. a promotion) when [slide] clears.
    val suppressed: Set<Int> = slide?.moves?.mapTo(HashSet()) { it.to } ?: emptySet()

    // Inside-coordinate labels (lichess style): file letters a–h along the bottom edge, rank numbers
    // 1–8 along the right edge, sized as a fraction of the cell so they scale with the board. The size
    // is derived from the measured [boardSizePx] (0 on the very first layout pass → labels sit out that
    // one frame, like the slide overlay's own boardSizePx guard), so no hardcoded sp goes stale on a
    // small (side-pane) or large (full-screen) board.
    val coordFontSize =
        if (boardSizePx > 0) {
            with(LocalDensity.current) { (boardSizePx / 8f * 0.24f).toSp() }
        } else {
            null
        }

    // testTag: the squares are anonymous clickable cells (no per-square semantics), so UI tests
    // address the board as one node and tap square centers via computed offsets (AppTestHarness).
    Box(modifier = modifier.aspectRatio(1f).testTag("chess-board").onSizeChanged { boardSizePx = it.width }) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (rowFromTop in 0..7) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    for (column in 0..7) {
                        val square = squareAt(column, rowFromTop, orientation)
                        val piece = if (square in suppressed) null else position.pieceAt(square)
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (isDarkSquare(square)) chess.darkSquare else chess.lightSquare)
                                    .let { base ->
                                        if (interaction != null) {
                                            base.clickable { interaction.onSquareTap(square) }
                                        } else {
                                            base
                                        }
                                    },
                        ) {
                            // Fade the selection / lift / legal-target overlays in and out instead of
                            // hard toggling. Display-only — no change to the tap/selection contract; the
                            // overlay stays composed only while its alpha is non-zero (so fade-out plays).
                            val selectedAlpha by animateFloatAsState(
                                targetValue = if (interaction?.selectedSquare == square) 1f else 0f,
                                label = "selectedTint",
                            )
                            if (selectedAlpha > 0f) {
                                Box(Modifier.matchParentSize().alpha(selectedAlpha).background(chess.selectedTint))
                            }
                            val liftAlpha by animateFloatAsState(
                                targetValue = if (square in highlightedSquares) 1f else 0f,
                                label = "liftHighlight",
                            )
                            if (liftAlpha > 0f) {
                                Box(Modifier.matchParentSize().alpha(liftAlpha).background(chess.liftHighlight))
                            }
                            piece?.let {
                                Image(
                                    painter = painterResource(pieceDrawable(it)),
                                    contentDescription = pieceDescription(it),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            val targetAlpha by animateFloatAsState(
                                targetValue =
                                    if (interaction != null &&
                                        square in interaction.targetSquares
                                    ) {
                                        1f
                                    } else {
                                        0f
                                    },
                                label = "targetMark",
                            )
                            if (targetAlpha > 0f) {
                                Canvas(modifier = Modifier.matchParentSize().alpha(targetAlpha)) {
                                    drawTargetMark(occupied = piece != null, color = chess.targetMark)
                                }
                            }
                            // Live reed-matrix overlay (S-09): a small neutral corner dot on each
                            // sensed-occupied square, drawn on top so it reads even over a piece. Absent
                            // for every call site that leaves [occupancyDots] null (Replay / Play / web).
                            if (hasOccupancyDot(occupancyDots, square)) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .padding(3.dp)
                                        .fillMaxSize(0.16f)
                                        .clip(CircleShape)
                                        .background(chess.occupancyDot),
                                )
                            }
                            // Inside coordinates, orientation-aware because [square] already accounts for
                            // it: the bottom row carries its file letter (bottom-left), the right column
                            // its rank number (top-right). Both derive from the real square, so a flipped
                            // board reads h→a / 8→1 exactly like a physical board turned around.
                            if (coordFontSize != null) {
                                if (rowFromTop == 7) {
                                    Text(
                                        text = ('a' + fileOf(square)).toString(),
                                        color = coordinateLabelColor(square, chess),
                                        fontSize = coordFontSize,
                                        fontWeight = FontWeight.Bold,
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(start = 2.dp, bottom = 1.dp),
                                    )
                                }
                                if (column == 7) {
                                    Text(
                                        text = (rankOf(square) + 1).toString(),
                                        color = coordinateLabelColor(square, chess),
                                        fontSize = coordFontSize,
                                        fontWeight = FontWeight.Bold,
                                        modifier =
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(end = 2.dp, top = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Fade the best-move arrow in when an evaluation arrives and out when it clears. The last
        // arrow is remembered so the fade-out still has geometry to draw after [bestMoveArrow] goes null.
        val arrowAlpha by animateFloatAsState(
            targetValue = if (bestMoveArrow != null) 1f else 0f,
            animationSpec = tween(durationMillis = 250),
            label = "bestMoveArrow",
        )
        var lastArrow by remember { mutableStateOf(bestMoveArrow) }
        if (bestMoveArrow != null) {
            lastArrow = bestMoveArrow
        }
        val arrow = lastArrow
        if (arrowAlpha > 0f && arrow != null) {
            Canvas(modifier = Modifier.fillMaxSize().alpha(arrowAlpha)) {
                drawBestMoveArrow(arrow, orientation, color = chess.bestMoveArrow)
            }
        }

        val activeSlide = slide
        if (activeSlide != null && boardSizePx > 0) {
            PieceSlideOverlay(
                slide = activeSlide,
                progress = slideProgress,
                boardSizePx = boardSizePx,
                orientation = orientation,
            )
        }
    }
}

/**
 * Absolutely-positioned layer that draws the moving piece(s) of [slide] over the static grid, from
 * each piece's source cell to its destination as [progress] runs 0→1. A captured piece (normal
 * capture or en passant) fades out at its square. The progress is read inside the layout/draw
 * lambdas (not at composition), so a running slide re-lays-out one or two small images per frame
 * rather than recomposing the 64-cell grid.
 */
@Composable
private fun PieceSlideOverlay(
    slide: BoardMoveAnimation,
    progress: Animatable<Float, *>,
    boardSizePx: Int,
    orientation: PieceColor,
) {
    val cellPx = boardSizePx / 8f
    val cellDp = with(LocalDensity.current) { cellPx.toDp() }

    val captured = slide.capturedPiece
    val capturedSquare = slide.capturedSquare
    if (captured != null && capturedSquare != null) {
        val (col, row) = cellOf(capturedSquare, orientation)
        Image(
            painter = painterResource(pieceDrawable(captured)),
            contentDescription = null,
            modifier =
                Modifier
                    .offset { IntOffset((col * cellPx).roundToInt(), (row * cellPx).roundToInt()) }
                    .size(cellDp)
                    .graphicsLayer { alpha = 1f - progress.value },
        )
    }

    for (move in slide.moves) {
        val (fromCol, fromRow) = cellOf(move.from, orientation)
        val (toCol, toRow) = cellOf(move.to, orientation)
        Image(
            painter = painterResource(pieceDrawable(move.piece)),
            contentDescription = null,
            modifier =
                Modifier
                    .offset {
                        val t = progress.value
                        IntOffset(
                            x = (lerp(fromCol.toFloat(), toCol.toFloat(), t) * cellPx).roundToInt(),
                            y = (lerp(fromRow.toFloat(), toRow.toFloat(), t) * cellPx).roundToInt(),
                        )
                    }.size(cellDp),
        )
    }
}

/**
 * Grid cell ([column] from the left, [rowFromTop] from the top) holding [square] for the given
 * [orientation] — the inverse of [squareAt], and the cell-space twin of the `center` math in
 * [drawBestMoveArrow]. Multiplied by the cell size it yields a piece image's top-left offset.
 */
private fun cellOf(
    square: Int,
    orientation: PieceColor,
): Pair<Int, Int> {
    val column = if (orientation == PieceColor.WHITE) fileOf(square) else 7 - fileOf(square)
    val rowFromTop = if (orientation == PieceColor.WHITE) 7 - rankOf(square) else rankOf(square)
    return column to rowFromTop
}

/** An empty legal target gets a centered dot; a capture target gets a ring around the piece. */
private fun DrawScope.drawTargetMark(
    occupied: Boolean,
    color: Color,
) {
    val extent = size.minDimension
    if (occupied) {
        drawCircle(
            color = color,
            radius = extent * 0.42f,
            style = Stroke(width = extent * 0.08f),
        )
    } else {
        drawCircle(color = color, radius = extent * 0.16f)
    }
}

private fun DrawScope.drawBestMoveArrow(
    arrow: BoardArrow,
    orientation: PieceColor,
    color: Color,
) {
    val cell = size.width / 8f

    fun center(square: Int): Offset {
        val column = if (orientation == PieceColor.WHITE) fileOf(square) else 7 - fileOf(square)
        val rowFromTop = if (orientation == PieceColor.WHITE) 7 - rankOf(square) else rankOf(square)
        return Offset(
            x = (column + 0.5f) * cell,
            y = (rowFromTop + 0.5f) * cell,
        )
    }

    val from = center(arrow.from)
    val to = center(arrow.to)
    val direction = to - from
    val length = direction.getDistance()
    if (length == 0f) return
    val unit = direction / length
    val headLength = cell * 0.45f
    val headBase = to - unit * headLength
    drawLine(
        color = color,
        start = from,
        end = headBase,
        strokeWidth = cell * 0.18f,
        cap = StrokeCap.Round,
    )
    val perpendicular = Offset(-unit.y, unit.x)
    val halfWidth = cell * 0.2f
    val left = headBase + perpendicular * halfWidth
    val right = headBase - perpendicular * halfWidth
    val head =
        Path().apply {
            moveTo(to.x, to.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
    drawPath(head, color)
}

/**
 * Square index rendered at grid cell ([column] from the left, [rowFromTop] from the top) for the
 * given [orientation] — the color shown at the bottom. White-bottom puts rank 8 on the top row
 * and the a-file on the left; black-bottom is the 180° rotation (rank 1 on top, h-file on the
 * left). This mapping is the single authority for tap→square under both orientations.
 */
internal fun squareAt(
    column: Int,
    rowFromTop: Int,
    orientation: PieceColor = PieceColor.WHITE,
): Int =
    when (orientation) {
        PieceColor.WHITE -> squareOf(file = column, rank = 7 - rowFromTop)
        PieceColor.BLACK -> squareOf(file = 7 - column, rank = rowFromTop)
    }

/** a1-dark convention: squares whose file+rank parity is even are dark. */
internal fun isDarkSquare(square: Int): Boolean = (fileOf(square) + rankOf(square)) % 2 == 0

/**
 * An inside-coordinate label paints in the *opposite* square color (a dark square gets the light-wood
 * hue, a light square the dark-wood hue), so the letter/number always reads against its own cell — the
 * classic lichess look, and it reuses the existing wood tokens rather than adding a new one.
 */
private fun coordinateLabelColor(
    square: Int,
    chess: ChessColors,
): Color = if (isDarkSquare(square)) chess.lightSquare else chess.darkSquare

/**
 * Whether the live reed-matrix overlay (S-09) draws a sensed dot on [square]: only when [occupancyDots]
 * is non-null (the play-board overlay is wired) and the square's bit is set, read h8-safe via the shared
 * [isOccupied] (square 63 is the sign bit). A null bitfield — every non-physical call site's default —
 * yields no dots, so Replay / Play / web render exactly as before.
 */
internal fun hasOccupancyDot(
    occupancyDots: Long?,
    square: Int,
): Boolean = occupancyDots != null && isOccupied(occupancyDots, square)

internal fun pieceDrawable(piece: Piece): DrawableResource =
    when (piece.color) {
        PieceColor.WHITE -> {
            when (piece.type) {
                PieceType.KING -> Res.drawable.piece_wk
                PieceType.QUEEN -> Res.drawable.piece_wq
                PieceType.ROOK -> Res.drawable.piece_wr
                PieceType.BISHOP -> Res.drawable.piece_wb
                PieceType.KNIGHT -> Res.drawable.piece_wn
                PieceType.PAWN -> Res.drawable.piece_wp
            }
        }

        PieceColor.BLACK -> {
            when (piece.type) {
                PieceType.KING -> Res.drawable.piece_bk
                PieceType.QUEEN -> Res.drawable.piece_bq
                PieceType.ROOK -> Res.drawable.piece_br
                PieceType.BISHOP -> Res.drawable.piece_bb
                PieceType.KNIGHT -> Res.drawable.piece_bn
                PieceType.PAWN -> Res.drawable.piece_bp
            }
        }
    }

private fun pieceDescription(piece: Piece): String {
    val color = if (piece.color == PieceColor.WHITE) "White" else "Black"
    val type =
        when (piece.type) {
            PieceType.KING -> "king"
            PieceType.QUEEN -> "queen"
            PieceType.ROOK -> "rook"
            PieceType.BISHOP -> "bishop"
            PieceType.KNIGHT -> "knight"
            PieceType.PAWN -> "pawn"
        }
    return "$color $type"
}
