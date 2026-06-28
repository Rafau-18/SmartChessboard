package org.rurbaniak.smartchessboard.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import org.rurbaniak.smartchessboard.domain.preferences.ThemeMode

/**
 * Slate Steel — a steel-blue accent palette shared across all three targets (no dynamic color, by
 * decision). The pinned anchors come from the plan; the remaining M3 roles are derived around the
 * accent for a coherent set. One consistent palette in both modes — only the wood board stays
 * constant (see [ChessColors]).
 */
private val SlateSteelLight: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF3B6EA5),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3F5),
        onPrimaryContainer = Color(0xFF0E1F30),
        secondary = Color(0xFF50657D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD7E2EE),
        onSecondaryContainer = Color(0xFF131C26),
        tertiary = Color(0xFF5A6573),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDDE3EB),
        onTertiaryContainer = Color(0xFF171C22),
        background = Color(0xFFF1F4F7),
        onBackground = Color(0xFF1C2530),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1C2530),
        surfaceVariant = Color(0xFFEAF0F6),
        onSurfaceVariant = Color(0xFF5A6573),
        outline = Color(0xFF8B97A6),
        outlineVariant = Color(0xFFC5CDD8),
    )

private val SlateSteelDark: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF6FA8DC),
        onPrimary = Color(0xFF0A131C),
        primaryContainer = Color(0xFF234561),
        onPrimaryContainer = Color(0xFFD3E3F5),
        secondary = Color(0xFFB4C6DA),
        onSecondary = Color(0xFF1E2C3A),
        secondaryContainer = Color(0xFF36465A),
        onSecondaryContainer = Color(0xFFD7E2EE),
        tertiary = Color(0xFFAEB9C8),
        onTertiary = Color(0xFF222831),
        tertiaryContainer = Color(0xFF424B57),
        onTertiaryContainer = Color(0xFFDDE3EB),
        background = Color(0xFF121821),
        onBackground = Color(0xFFE3E9F0),
        surface = Color(0xFF1B2430),
        onSurface = Color(0xFFE3E9F0),
        surfaceVariant = Color(0xFF202B39),
        onSurfaceVariant = Color(0xFF93A1B3),
        outline = Color(0xFF5E6A78),
        outlineVariant = Color(0xFF3A4350),
    )

/**
 * The app-wide theme. Resolves dark/light from [mode] + the OS setting, applies the Material 3
 * Expressive theme with the Slate Steel scheme, and provides the matching [ChessColors] token set.
 *
 * Expressive is opt-in per-file (the alpha artifact is already pinned — no catalog change); keeping
 * the [OptIn] local rather than a global compiler arg keeps the experimental surface visible.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    MaterialExpressiveTheme(
        colorScheme = if (dark) SlateSteelDark else SlateSteelLight,
    ) {
        CompositionLocalProvider(
            LocalChessColors provides if (dark) ChessColorsDark else ChessColorsLight,
            content = content,
        )
    }
}

/**
 * Compact label for a [ThemeMode] — the single source for the theme-cycle controls (History top bar
 * and the Sign-in screen), where it doubles as the live indicator of the active mode.
 */
internal fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "Auto"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
