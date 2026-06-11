package org.rurbaniak.smartchessboard.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
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
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = "com.smartchessboard"
            host = "callback"
        }
    }
}
