package org.rurbaniak.smartchessboard.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Binds the Nav3 back stack to the host's history model. On web (wasmJs) the real `actual` maps the
 * back stack to browser history so the browser Back/Forward buttons move through the app's stack
 * (Replay ↔ History) and the route is reflected in the URL fragment. On Android/iOS this is a no-op
 * — those platforms already have first-class back handling (system back / start-edge pan).
 *
 * Called once where `NavDisplay` is hosted (App.kt SignedIn branch).
 */
@Composable
expect fun bindBrowserNavigation(backStack: NavBackStack<NavKey>)
