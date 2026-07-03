package org.rurbaniak.smartchessboard.presentation.layout

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveLayoutTest {
    // screenChrome: the 480 dp height boundary.

    @Test
    fun chromeIsLeftRailBelow480Height() {
        assertEquals(ScreenChrome.LeftRail, screenChrome(WindowSizeClass(840, 479)))
    }

    @Test
    fun chromeIsTopBarAt480Height() {
        assertEquals(ScreenChrome.TopBar, screenChrome(WindowSizeClass(840, 480)))
    }

    // boardArrangement: the 480 dp height and 840 dp width boundaries.

    @Test
    fun arrangementIsSidePaneWhenHeightCompact() {
        assertEquals(BoardArrangement.SidePane, boardArrangement(WindowSizeClass(600, 479)))
    }

    @Test
    fun arrangementIsSidePaneWhenWidthExpanded() {
        assertEquals(BoardArrangement.SidePane, boardArrangement(WindowSizeClass(840, 900)))
    }

    @Test
    fun arrangementIsColumnBelowBothBreakpoints() {
        assertEquals(BoardArrangement.Column, boardArrangement(WindowSizeClass(839, 480)))
    }

    @Test
    fun arrangementFlipsAt840Width() {
        assertEquals(BoardArrangement.SidePane, boardArrangement(WindowSizeClass(840, 480)))
    }

    // boardResizeEnabled: width-expanded AND NOT height-compact.

    @Test
    fun resizeEnabledOnTrueWide() {
        assertTrue(boardResizeEnabled(WindowSizeClass(840, 480)))
    }

    @Test
    fun resizeDisabledOnLandscapeFlagship() {
        // Width-expanded but height-compact (a Pixel-8-class 914x411 window): auto-fit, no handle.
        assertFalse(boardResizeEnabled(WindowSizeClass(840, 479)))
    }

    @Test
    fun resizeDisabledBelowExpandedWidth() {
        assertFalse(boardResizeEnabled(WindowSizeClass(839, 900)))
    }

    // 600/900 sanity + production-shaped bucketing through BREAKPOINTS_V1.

    @Test
    fun mediumTallWindowIsPortraitShaped() {
        val windowSizeClass = WindowSizeClass(600, 900)
        assertEquals(ScreenChrome.TopBar, screenChrome(windowSizeClass))
        assertEquals(BoardArrangement.Column, boardArrangement(windowSizeClass))
        assertFalse(boardResizeEnabled(windowSizeClass))
    }

    @Test
    fun bucketedPhoneClassesMatchPolicy() {
        // Raw window sizes bucketed exactly like the App root computes them.
        val landscapePhone = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 914, heightDp = 411)
        assertEquals(ScreenChrome.LeftRail, screenChrome(landscapePhone))
        assertEquals(BoardArrangement.SidePane, boardArrangement(landscapePhone))
        assertFalse(boardResizeEnabled(landscapePhone))

        val portraitPhone = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 411, heightDp = 914)
        assertEquals(ScreenChrome.TopBar, screenChrome(portraitPhone))
        assertEquals(BoardArrangement.Column, boardArrangement(portraitPhone))
        assertFalse(boardResizeEnabled(portraitPhone))
    }
}
