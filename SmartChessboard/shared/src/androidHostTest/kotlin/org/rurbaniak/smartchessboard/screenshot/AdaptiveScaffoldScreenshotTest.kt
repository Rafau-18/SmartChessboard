package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
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
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveActionButton
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveBackButton
import org.rurbaniak.smartchessboard.presentation.components.AdaptiveScaffold

/**
 * Freezes the two `AdaptiveScaffold` chromes: the M3 top bar (title + labelled text actions) and
 * the compact-height left rail (icon-only stack, title dropped). The same slot set renders in
 * both, including the `selected` emphasis tri-state on the actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xhdpi")
class AdaptiveScaffoldScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun chromeGolden(
        name: String,
        dark: Boolean,
        windowSizeClass: androidx.window.core.layout.WindowSizeClass,
        size: DpSize,
    ) {
        compose.golden(name = name, dark = dark, windowSizeClass = windowSizeClass, size = size) {
            AdaptiveScaffold(
                title = { Text("Screen title") },
                navigationIcon = { AdaptiveBackButton(onBack = {}) },
                actions = {
                    AdaptiveActionButton(label = "New", icon = Icons.Filled.Add, onClick = {})
                    AdaptiveActionButton(
                        label = "Table",
                        icon = Icons.Filled.Refresh,
                        onClick = {},
                        selected = true,
                    )
                },
            ) { padding ->
                PlaceholderContent(padding)
            }
        }
    }

    @Test
    fun topBarLight() {
        chromeGolden(
            name = "scaffold_adaptive_topbar_light",
            dark = false,
            windowSizeClass = PORTRAIT_MEDIUM,
            size = DpSize(412.dp, 892.dp),
        )
    }

    @Test
    fun topBarDark() {
        chromeGolden(
            name = "scaffold_adaptive_topbar_dark",
            dark = true,
            windowSizeClass = PORTRAIT_MEDIUM,
            size = DpSize(412.dp, 892.dp),
        )
    }

    @Test
    @Config(qualifiers = "w892dp-h412dp-land-xhdpi")
    fun leftRailLight() {
        chromeGolden(
            name = "scaffold_adaptive_leftrail_light",
            dark = false,
            windowSizeClass = LANDSCAPE_COMPACT,
            size = DpSize(892.dp, 412.dp),
        )
    }

    @Test
    @Config(qualifiers = "w892dp-h412dp-land-xhdpi")
    fun leftRailDark() {
        chromeGolden(
            name = "scaffold_adaptive_leftrail_dark",
            dark = true,
            windowSizeClass = LANDSCAPE_COMPACT,
            size = DpSize(892.dp, 412.dp),
        )
    }
}

@Composable
private fun PlaceholderContent(padding: PaddingValues) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("Content area", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
