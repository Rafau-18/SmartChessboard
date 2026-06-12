package org.rurbaniak.smartchessboard.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.compose.auth.ComposeAuth
import io.github.jan.supabase.compose.auth.googleNativeLogin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import org.rurbaniak.smartchessboard.BuildKonfig

fun createAppSupabaseClient(): SupabaseClient {
    check(BuildKonfig.SUPABASE_URL.isNotEmpty() && BuildKonfig.SUPABASE_ANON_KEY.isNotEmpty()) {
        "Missing SUPABASE_URL / SUPABASE_ANON_KEY (inject via local.properties or -P)"
    }
    return createSupabaseClient(
        supabaseUrl = BuildKonfig.SUPABASE_URL,
        supabaseKey = BuildKonfig.SUPABASE_ANON_KEY,
    ) {
        install(Postgrest)
        // Edge Function invocation (lichess-eval, contract §3.3).
        install(Functions)
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "com.smartchessboard"
            host = "callback"
        }
        // Native Google sign-in (Credential Manager) on Android; on iOS/web the platform
        // has no native provider, so rememberSignInWithGoogle() falls back to the browser
        // flow. The Web client ID (public, RLS-irrelevant) may be empty — only the Android
        // native sheet needs it; the browser fallback works regardless.
        install(ComposeAuth) {
            googleNativeLogin(BuildKonfig.GOOGLE_SERVER_CLIENT_ID)
        }
    }
}
