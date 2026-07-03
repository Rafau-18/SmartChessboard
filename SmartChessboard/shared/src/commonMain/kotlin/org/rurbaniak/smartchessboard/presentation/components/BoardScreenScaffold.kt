package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.presentation.layout.BoardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.layout.boardArrangement
import org.rurbaniak.smartchessboard.presentation.layout.isHeightCompact

/** Gap between the board pane and the side panel (Replay's proven two-pane gap). */
private val PANE_GAP = 12.dp

/** The board screens' outer padding — the same 16 dp every screen applies today. */
private val SCREEN_PADDING = 16.dp

/**
 * Vertical outer padding in the side-pane arrangement at compact height: every dp above/below the
 * board comes straight out of the board's square, so a ~360 dp window keeps only a hairline margin.
 */
private val SCREEN_PADDING_V_COMPACT = 4.dp

/**
 * One arrangement authority for the board screens (Play / PhysicalPlay / Replay), driven by
 * [boardArrangement]: at compact height or expanded width the [board] fills the height beside a
 * scrolling side panel; otherwise the classic portrait column. Generalizes Replay's two-pane layout.
 *
 * Slots:
 * - [banner] — status/message content in a fixed-height reserved slot ([bannerSlotHeight]); the slot
 *   is laid out even when `null`, so a banner appearing or disappearing never moves the board (the
 *   no-jump invariant). In the side-pane arrangement it pins to the top of the panel — never above
 *   the whole row.
 * - [board] — the board (typically `ResizableBoardBox` + `ChessBoardView`; the `BoardWithEvalBar`
 *   reserved-width pattern composes here unchanged). In the side-pane arrangement the slot has a
 *   bounded height — the pane height is the board's exact budget, insets and chrome already
 *   consumed upstream.
 * - [panelContent] — the screen's sections (controls, move list, …), each capping its own width via
 *   [SECTION_MAX_WIDTH]. Renders under the board in the column arrangement, in the side panel's own
 *   scroll otherwise.
 *
 * [contentWidthExpansion] (0..1) slides the side-pane arrangement's width cap from
 * [CONTENT_MAX_WIDTH] toward the full window width. Replay maps its board-enlargement fraction here
 * so dragging the board past its default size spills past the default margins; screens without that
 * behavior keep the default 0 (hard cap).
 */
@Composable
fun BoardScreenScaffold(
    banner: (@Composable () -> Unit)?,
    board: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bannerSlotHeight: Dp = BANNER_SLOT_HEIGHT,
    contentWidthExpansion: Float = 0f,
    panelContent: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        when (boardArrangement(LocalWindowSizeClass.current)) {
            BoardArrangement.Column -> {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxHeight()
                            .align(Alignment.TopCenter)
                            .verticalScroll(rememberScrollState())
                            .padding(SCREEN_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BannerSlot(
                        banner = banner,
                        height = bannerSlotHeight,
                        modifier = Modifier.widthIn(max = SECTION_MAX_WIDTH).fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    board()
                    panelContent()
                }
            }

            BoardArrangement.SidePane -> {
                // The container cap slides from CONTENT_MAX_WIDTH toward the full window width as
                // [contentWidthExpansion] grows, so an enlarged board can spill past the default
                // margins (Replay's drag-to-grow) instead of being trapped inside them.
                val containerMax =
                    if (maxWidth <= CONTENT_MAX_WIDTH) {
                        maxWidth
                    } else {
                        CONTENT_MAX_WIDTH + (maxWidth - CONTENT_MAX_WIDTH) * contentWidthExpansion.coerceIn(0f, 1f)
                    }
                // Compact height trades margins and panel width for board size: hairline vertical
                // padding and a tighter panel floor, so the board's square is as tall as the window
                // allows. Non-compact (wide) keeps the regular paddings and the roomier panel.
                val heightCompact = LocalWindowSizeClass.current.isHeightCompact
                val verticalPadding = if (heightCompact) SCREEN_PADDING_V_COMPACT else SCREEN_PADDING
                val rowWidth = containerMax - SCREEN_PADDING * 2
                val panelWidth =
                    sidePanelWidth(
                        rowWidth = rowWidth,
                        boardTarget = maxHeight - verticalPadding * 2,
                        panelMin = if (heightCompact) SIDE_PANEL_MIN_WIDTH_COMPACT else SIDE_PANEL_MIN_WIDTH,
                    )
                Row(
                    modifier =
                        Modifier
                            .widthIn(max = containerMax)
                            .fillMaxSize()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = SCREEN_PADDING, vertical = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
                ) {
                    // Top-aligned like the panel beside it — a board smaller than the pane (wide
                    // screens) must not float to the vertical centre while the panel hugs the top.
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        board()
                    }
                    Column(
                        modifier = Modifier.width(panelWidth).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BannerSlot(
                            banner = banner,
                            height = bannerSlotHeight,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = panelContent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The reserved banner slot: fixed [height] whether or not [banner] content is present, so banner
 * transitions never re-lay-out what sits around the slot. Oversized content must bound itself
 * (internal scroll / ellipsis) inside the slot rather than grow it.
 */
@Composable
private fun BannerSlot(
    banner: (@Composable () -> Unit)?,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.height(height), contentAlignment = Alignment.Center) {
        banner?.invoke()
    }
}

/**
 * Width of the side panel in the side-pane arrangement. The board is the primary: the panel gets
 * what remains of [rowWidth] after a height-filling board ([boardTarget] — the pane's height budget)
 * and the pane gap, clamped to [panelMin]..[SECTION_MAX_WIDTH] ([SIDE_PANEL_MIN_WIDTH] normally,
 * the tighter [SIDE_PANEL_MIN_WIDTH_COMPACT] at compact height so a narrow-landscape phone keeps a
 * full-height board). The floor never crushes the board below [MIN_BOARD_SIDE], and a degenerate
 * window resolves to zero rather than a negative width. Pure, so it is unit-testable on every target.
 */
internal fun sidePanelWidth(
    rowWidth: Dp,
    boardTarget: Dp,
    panelMin: Dp = SIDE_PANEL_MIN_WIDTH,
): Dp =
    (rowWidth - boardTarget - PANE_GAP)
        .coerceIn(panelMin, SECTION_MAX_WIDTH)
        .coerceAtMost(rowWidth - MIN_BOARD_SIDE - PANE_GAP)
        .coerceAtLeast(0.dp)
