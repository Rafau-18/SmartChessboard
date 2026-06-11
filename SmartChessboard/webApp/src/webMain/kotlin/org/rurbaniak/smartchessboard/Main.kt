package org.rurbaniak.smartchessboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.rurbaniak.smartchessboard.di.initKoin

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Koin must be up before the first composition resolves a ViewModel. Creating the
    // Supabase client here also lets the Auth plugin consume the OAuth redirect URL
    // (PKCE code) during init — the root shows Restoring until that resolves.
    initKoin()
    ComposeViewport {
        App()
    }
}
