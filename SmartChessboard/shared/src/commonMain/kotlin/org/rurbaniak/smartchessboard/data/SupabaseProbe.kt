package org.rurbaniak.smartchessboard.data

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonObject
import org.rurbaniak.smartchessboard.BuildKonfig

private val supabase by lazy {
    createSupabaseClient(
        supabaseUrl = BuildKonfig.SUPABASE_URL,
        supabaseKey = BuildKonfig.SUPABASE_ANON_KEY,
    ) {
        install(Postgrest)
    }
}

sealed interface ProbeResult {
    // 0 rows as anon is the success path: RLS denies rows but the request returns 200.
    data class Ok(
        val visibleRows: Int,
    ) : ProbeResult

    data class Error(
        val message: String,
    ) : ProbeResult
}

suspend fun probeSupabase(): ProbeResult {
    if (BuildKonfig.SUPABASE_URL.isEmpty() || BuildKonfig.SUPABASE_ANON_KEY.isEmpty()) {
        return ProbeResult.Error("missing SUPABASE_URL / SUPABASE_ANON_KEY (inject via local.properties or -P)")
    }
    return try {
        val rows = supabase.from("position_evals").select().decodeList<JsonObject>()
        ProbeResult.Ok(rows.size)
    } catch (t: Throwable) {
        ProbeResult.Error(t.message ?: "unknown error")
    }
}
