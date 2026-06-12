package org.rurbaniak.smartchessboard.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/** iOS uses the start-edge pan / system back via Nav3 — no browser history to bind. */
@Composable
actual fun bindBrowserNavigation(backStack: NavBackStack<NavKey>) {
    // no-op
}
