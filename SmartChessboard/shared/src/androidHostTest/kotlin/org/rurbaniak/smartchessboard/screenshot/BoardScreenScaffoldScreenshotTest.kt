package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.rurbaniak.smartchessboard.presentation.components.BoardScreenScaffold

/**
 * Freezes the three `BoardScreenScaffold` arrangements from the landscape merge — Column,
 * SidePane at compact height, SidePane at expanded width — with deterministic placeholder slot
 * content (solid theme blocks + text): pane split, reserved banner slot, and paddings are the
 * subject here, board rendering is deliberately not (that's `ChessBoardViewScreenshotTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class BoardScreenScaffoldScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun arrangementGolden(
        name: String,
        dark: Boolean,
        windowSizeClass: androidx.window.core.layout.WindowSizeClass,
        size: DpSize,
    ) {
        compose.golden(name = name, dark = dark, windowSizeClass = windowSizeClass, size = size) {
            BoardScreenScaffold(
                banner = { PlaceholderBanner() },
                board = { PlaceholderBoard() },
            ) {
                PlaceholderPanelSections()
            }
        }
    }

    @Test
    fun columnLight() {
        arrangementGolden(
            name = "scaffold_board_column_light",
            dark = false,
            windowSizeClass = PORTRAIT_MEDIUM,
            size = DpSize(412.dp, 892.dp),
        )
    }

    @Test
    fun columnDark() {
        arrangementGolden(
            name = "scaffold_board_column_dark",
            dark = true,
            windowSizeClass = PORTRAIT_MEDIUM,
            size = DpSize(412.dp, 892.dp),
        )
    }

    @Test
    @Config(qualifiers = "w892dp-h412dp-land-xhdpi")
    fun sidePaneCompactLight() {
        arrangementGolden(
            name = "scaffold_board_sidepane_compact_light",
            dark = false,
            windowSizeClass = LANDSCAPE_COMPACT,
            size = DpSize(892.dp, 412.dp),
        )
    }

    @Test
    @Config(qualifiers = "w892dp-h412dp-land-xhdpi")
    fun sidePaneCompactDark() {
        arrangementGolden(
            name = "scaffold_board_sidepane_compact_dark",
            dark = true,
            windowSizeClass = LANDSCAPE_COMPACT,
            size = DpSize(892.dp, 412.dp),
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun sidePaneWideLight() {
        arrangementGolden(
            name = "scaffold_board_sidepane_wide_light",
            dark = false,
            windowSizeClass = WIDE_EXPANDED,
            size = DpSize(1280.dp, 800.dp),
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun sidePaneWideDark() {
        arrangementGolden(
            name = "scaffold_board_sidepane_wide_dark",
            dark = true,
            windowSizeClass = WIDE_EXPANDED,
            size = DpSize(1280.dp, 800.dp),
        )
    }
}

@Composable
internal fun PlaceholderBanner() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("Banner slot", color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
internal fun PlaceholderBoard() {
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("Board slot", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun PlaceholderPanelSections() {
    repeat(3) { index ->
        Box(
            modifier =
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("Panel section ${index + 1}", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
