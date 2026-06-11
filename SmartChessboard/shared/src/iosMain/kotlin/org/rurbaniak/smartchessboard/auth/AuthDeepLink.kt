package org.rurbaniak.smartchessboard.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import org.koin.mp.KoinPlatform
import platform.Foundation.NSURL

/**
 * Forwards a URL opened by the system (SwiftUI `onOpenURL`) to the Supabase Auth
 * deep-link handler (OAuth callback, contract §4.2). Swift cannot reach supabase-kt
 * types directly, so this is the bridge surface.
 */
fun handleAuthDeeplink(url: NSURL) {
    KoinPlatform.getKoin().get<SupabaseClient>().handleDeeplinks(url)
}
