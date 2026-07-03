package org.rurbaniak.smartchessboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.layout.ScreenChrome
import org.rurbaniak.smartchessboard.presentation.layout.screenChrome

/**
 * The app's one screen-chrome authority, driven by [screenChrome]: a regular M3 [Scaffold] +
 * [TopAppBar] normally, or — at compact height (landscape phone, short browser window) — a left
 * vertical action rail hosting [navigationIcon] and [actions] with the [title] dropped, so a ~64 dp
 * bar doesn't eat a ~360 dp window. The switch is window-shape-driven, never platform-driven: a
 * short web window gets the rail too, and restoring the height restores the top bar.
 *
 * Slots mirror what screens previously passed to [TopAppBar] directly. [actions] is deliberately
 * scope-free (not `RowScope`) so the same composables lay out horizontally in the bar and stack
 * vertically in the rail. Rail touch targets stay >= 48 dp via M3's minimum-interactive-size
 * enforcement on the buttons themselves. `keepScreenOn` stays a per-screen concern, passed via
 * [modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    when (screenChrome(LocalWindowSizeClass.current)) {
        ScreenChrome.TopBar -> {
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = title,
                        navigationIcon = navigationIcon,
                        actions = { actions() },
                    )
                },
            ) { padding ->
                content(padding)
            }
        }

        ScreenChrome.LeftRail -> {
            Scaffold(
                modifier = modifier,
                // Scaffold's default contentWindowInsets is systemBars only (Phase 1 audit) — not
                // enough here, so the rail and the content own their inset consumption below.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LeftActionRail(navigationIcon = navigationIcon, actions = actions)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        // The rail owns the leading edge; the content still needs the trailing edge
                        // (the cutout sits there in the other rotation) plus the vertical system bars.
                        content(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End + WindowInsetsSides.Vertical)
                                .asPaddingValues(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The compact-height chrome: Back above the screen's actions, stacked on the leading edge. The rail
 * sits on a long edge — exactly where the landscape camera cutout lives — so it consumes
 * `displayCutout` + `systemBars` on its leading side (either landscape rotation is safe: the other
 * rotation's cutout lands on the content's trailing edge, handled above).
 */
@Composable
private fun LeftActionRail(
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                )
                // As wide as its widest action label; scrolls if a short window can't fit them all.
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navigationIcon()
        actions()
    }
}
