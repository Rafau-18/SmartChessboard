package org.rurbaniak.smartchessboard.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.github.terrakok.navigation3.browser.HierarchicalBrowserNavigation
import com.github.terrakok.navigation3.browser.buildBrowserHistoryFragment

/**
 * Maps the Nav3 back stack to browser history (the binding is **not** in the base multiplatform
 * Nav3 library at 1.1.1 — this uses terrakok's `navigation3-browser`). The current top destination
 * is reflected in the URL **hash fragment** after `#`, and the browser Back button pops the in-app
 * back stack.
 *
 * Uses the **hierarchical** variant (not chronological): browser Back follows the *current* back
 * stack via the NavigationEvent dispatcher (it drives `NavDisplay`'s `onBack`), rather than
 * replaying every historical stack state. This matters for **replace** navigations — creating a game
 * (NewGame → Play) and analysing a finished game (Play → Replay) both replace the top entry. The
 * chronological variant left the replaced-away screen reachable via browser Back (you'd land on the
 * player-name form, or on a frozen finished board); the hierarchical variant mirrors the live stack,
 * so browser Back goes straight to History. This app is never deeper than one level below History,
 * so a single browser Back always returns there.
 *
 * Trade-off: the hierarchical variant has no URL→state restore, so a page reload starts at History
 * regardless of the fragment — acceptable, since shareable / deep-link URLs are intentionally out of
 * MVP scope. The one-shot `BrowserHistoryIsInUse` bind limitation is unchanged (see lessons.md).
 *
 * The fragment is built with [buildBrowserHistoryFragment] so it is a proper `#…`-prefixed hash
 * (e.g. `#replay?id=<uuid>`). This matters: a raw, un-prefixed string like `"replay/<id>"` makes the
 * library call `history.pushState(…, "replay/<id>")`, which changes the document **pathname** — and
 * then Compose resources loaded by relative path (`./composeResources/…`, e.g. the piece drawables)
 * 404. A `#…` fragment only changes the hash, so relative resources keep resolving against the root.
 *
 * Called once where `NavDisplay` is hosted (App.kt SignedIn branch), as a sibling before it — the
 * `LocalNavigationEventDispatcherOwner` it needs is in scope there (matches the library's sample).
 */
@Composable
actual fun bindBrowserNavigation(backStack: NavBackStack<NavKey>) {
    HierarchicalBrowserNavigation {
        when (val key = backStack.lastOrNull()) {
            is ReplayKey -> buildBrowserHistoryFragment("replay", mapOf("id" to key.gameId))

            is PlayKey -> buildBrowserHistoryFragment("play", mapOf("id" to key.gameId))

            // Web never routes to a physical game (it opens in Replay), but the mappings keep the when total.
            is ConnectionKey -> buildBrowserHistoryFragment("connect", mapOf("id" to key.gameId))

            is PhysicalPlayKey -> buildBrowserHistoryFragment("physical", mapOf("id" to key.gameId))

            NewGameKey -> buildBrowserHistoryFragment("new")

            else -> buildBrowserHistoryFragment("history")
        }
    }
}
