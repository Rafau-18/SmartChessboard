package org.rurbaniak.smartchessboard.auth

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.handleDeeplinks
import org.koin.mp.KoinPlatform

/**
 * Forwards an incoming intent to the Supabase Auth deep-link handler (OAuth callback,
 * contract §4.2). Exposed here because supabase-kt is an implementation detail of
 * :shared and not on the :androidApp compile classpath.
 */
fun handleAuthDeeplink(intent: Intent) {
    KoinPlatform.getKoin().get<SupabaseClient>().handleDeeplinks(intent)
}
