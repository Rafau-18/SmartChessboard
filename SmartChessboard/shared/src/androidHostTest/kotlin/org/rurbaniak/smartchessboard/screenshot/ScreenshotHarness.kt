package org.rurbaniak.smartchessboard.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.LosslessWebPImageIoFormat
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode
import org.rurbaniak.smartchessboard.presentation.layout.LocalWindowSizeClass
import org.rurbaniak.smartchessboard.presentation.theme.AppTheme

/**
 * Named window-size classes for the three arrangements of the adaptive layout policy
 * ([org.rurbaniak.smartchessboard.presentation.layout.boardArrangement] /
 * [org.rurbaniak.smartchessboard.presentation.layout.screenChrome]). Every golden MUST pin one
 * explicitly — [LocalWindowSizeClass] defaults to `WindowSizeClass(0, 0)` (height-compact), so a
 * forgotten provider silently renders the landscape SidePane + LeftRail arrangement, not portrait.
 */

/** Portrait phone (412×892 dp): Column arrangement + TopBar chrome. */
val PORTRAIT_MEDIUM: WindowSizeClass =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 412, heightDp = 892)

/** Landscape phone (892×412 dp, height-compact): SidePane arrangement + LeftRail chrome. */
val LANDSCAPE_COMPACT: WindowSizeClass =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 892, heightDp = 412)

/** Tablet / desktop (1280×800 dp, width-expanded): SidePane + TopBar, board resize enabled. */
val WIDE_EXPANDED: WindowSizeClass =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp = 1280, heightDp = 800)

/** Square board shot; component shots that need another aspect pass their own [DpSize]. */
val DEFAULT_SHOT: DpSize = DpSize(412.dp, 412.dp)

/** Committed golden location (versioned source, never `build/`), relative to the `shared/` module dir. */
private const val SNAPSHOTS_DIR = "src/androidHostTest/snapshots"

private const val GOLDEN_TAG = "golden-root"

/**
 * Shared comparison/record options for every golden: zero changed pixels allowed, with a small
 * per-pixel color distance so platform antialiasing alone never fails a verify; goldens are stored
 * at half scale to keep the committed tree small. Pinned in code (not gradle.properties) so the
 * options cannot drift between invocation styles.
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
private val GOLDEN_OPTIONS =
    RoborazziOptions(
        recordOptions =
            RoborazziOptions.RecordOptions(
                resizeScale = 0.5,
                // Lossless WebP (VP8L via webp-imageio): a lossy golden never byte-matches the
                // next render, so verify would fail on unchanged code.
                imageIoFormat = LosslessWebPImageIoFormat(),
            ),
        compareOptions =
            RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0.007f, hShift = 0, vShift = 0),
            ),
    )

/**
 * Renders [content] under pinned inputs — [AppTheme] dark/light, an explicit [windowSizeClass]
 * via [LocalWindowSizeClass], and a fixed [size] — then records/verifies it as
 * `src/androidHostTest/snapshots/<name>.webp`. One-liner per golden so no test can forget the
 * window-class trap; content must be state-only (no clocks, no network). [prepare] runs between
 * composition and capture for the rare shot whose state sits behind the test clock (e.g. the
 * sync hint's show delay) — advance [ComposeContentTestRule.mainClock] there, nothing else.
 */
fun ComposeContentTestRule.golden(
    name: String,
    dark: Boolean,
    windowSizeClass: WindowSizeClass = PORTRAIT_MEDIUM,
    size: DpSize = DEFAULT_SHOT,
    prepare: ComposeContentTestRule.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    setContent {
        AppTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                Surface(modifier = Modifier.exactShotSize(size).testTag(GOLDEN_TAG)) {
                    content()
                }
            }
        }
    }
    prepare()
    onNodeWithTag(GOLDEN_TAG).captureRoboImage(
        filePath = "$SNAPSHOTS_DIR/$name.webp",
        roborazziOptions = GOLDEN_OPTIONS,
    )
}

/**
 * Measures the shot surface at exactly [size], ignoring incoming window constraints — shots must
 * not silently shrink when a variant (e.g. [WIDE_EXPANDED]) is larger than the Robolectric window.
 */
private fun Modifier.exactShotSize(size: DpSize): Modifier =
    layout { measurable, _ ->
        val width = size.width.roundToPx()
        val height = size.height.roundToPx()
        val placeable =
            measurable.measure(
                androidx.compose.ui.unit.Constraints
                    .fixed(width, height),
            )
        layout(width, height) { placeable.place(0, 0) }
    }
