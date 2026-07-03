package org.rurbaniak.smartchessboard.presentation.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The app-wide window classification. Computed once at the App root from the real window size and
 * provided here, so every screen reads the same value from the same measurement base (the window's
 * `containerSize` — never local layout constraints). The compact × compact default only applies
 * where nothing provides the real class (previews, tests).
 */
val LocalWindowSizeClass = compositionLocalOf { WindowSizeClass(0, 0) }

/**
 * The current window's official size class, bucketed via [WindowSizeClass.BREAKPOINTS_V1]
 * (width 600/840 dp, height 480/900 dp) — the value changes only when a breakpoint is crossed, not
 * on every resize pixel. Call at the App root and expose through [LocalWindowSizeClass]; screens
 * read the local instead of calling this.
 */
@Composable
fun currentWindowSizeClass(): WindowSizeClass {
    val containerSize = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
            widthDp =
                containerSize.width
                    .toDp()
                    .value
                    .toInt(),
            heightDp =
                containerSize.height
                    .toDp()
                    .value
                    .toInt(),
        )
    }
}

/** Which chrome a screen renders: the M3 top bar, or a left vertical action rail at compact height. */
enum class ScreenChrome { TopBar, LeftRail }

/** How a board screen arranges its content: the portrait column, or board beside a scrolling side panel. */
enum class BoardArrangement { Column, SidePane }

/** Height-compact (< 480 dp): a phone in landscape, or a short browser window. */
internal val WindowSizeClass.isHeightCompact: Boolean
    get() = !isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

/** Width-expanded (>= 840 dp): tablet, desktop, or a wide browser window. */
internal val WindowSizeClass.isWidthExpanded: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/** [ScreenChrome.LeftRail] iff height-compact — a 64 dp top bar costs too much of a ~360–430 dp window. */
fun screenChrome(windowSizeClass: WindowSizeClass): ScreenChrome =
    if (windowSizeClass.isHeightCompact) ScreenChrome.LeftRail else ScreenChrome.TopBar

/**
 * [BoardArrangement.SidePane] iff height-compact (landscape phone) or width-expanded
 * (tablet / desktop / wide web); the portrait column otherwise.
 */
fun boardArrangement(windowSizeClass: WindowSizeClass): BoardArrangement =
    if (windowSizeClass.isHeightCompact || windowSizeClass.isWidthExpanded) {
        BoardArrangement.SidePane
    } else {
        BoardArrangement.Column
    }

/**
 * The corner resize handle + persisted board-size fraction apply only on a true wide screen:
 * width-expanded and not height-compact. A landscape phone auto-fits instead.
 */
fun boardResizeEnabled(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthExpanded && !windowSizeClass.isHeightCompact
