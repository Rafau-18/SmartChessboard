package org.rurbaniak.smartchessboard.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.github.terrakok.navigation3.browser.ChronologicalBrowserNavigation
import com.github.terrakok.navigation3.browser.buildBrowserHistoryFragment
import com.github.terrakok.navigation3.browser.getBrowserHistoryFragmentName
import com.github.terrakok.navigation3.browser.getBrowserHistoryFragmentParameters

/**
 * Maps the Nav3 back stack to browser history (the binding is **not** in the base multiplatform
 * Nav3 library at 1.1.1 — this uses terrakok's `navigation3-browser`). Each push/pop reflects in the
 * URL **hash fragment** after `#`, and the browser Back/Forward buttons drive the stack instead of
 * leaving the site.
 *
 * The keys are built with [buildBrowserHistoryFragment] so they are proper `#…`-prefixed fragments
 * (e.g. `#replay?id=<uuid>`). This matters: returning a raw, un-prefixed string like `"replay/<id>"`
 * makes the library call `history.pushState(…, "replay/<id>")`, which changes the document
 * **pathname** — and then Compose resources loaded by relative path (`./composeResources/…`, e.g. the
 * piece drawables) 404. A `#…` fragment only changes the hash, so relative resources keep resolving
 * against the site root.
 */
@Composable
actual fun bindBrowserNavigation(backStack: NavBackStack<NavKey>) {
    ChronologicalBrowserNavigation(
        backStack = backStack,
        saveKey = { key ->
            when (key) {
                is ReplayKey -> buildBrowserHistoryFragment("replay", mapOf("id" to key.gameId))
                else -> buildBrowserHistoryFragment("history")
            }
        },
        restoreKey = { fragment ->
            when (getBrowserHistoryFragmentName(fragment)) {
                "replay" -> {
                    getBrowserHistoryFragmentParameters(fragment)["id"]
                        ?.let { ReplayKey(it) } ?: HistoryKey
                }

                else -> {
                    HistoryKey
                }
            }
        },
    )
}
