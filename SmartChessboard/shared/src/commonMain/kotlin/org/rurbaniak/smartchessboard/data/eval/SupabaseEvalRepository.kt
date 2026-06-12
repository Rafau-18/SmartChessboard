package org.rurbaniak.smartchessboard.data.eval

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.rurbaniak.smartchessboard.domain.eval.EvalOutcome
import org.rurbaniak.smartchessboard.domain.eval.EvalRepository

@Serializable
private data class EvalRequestDto(
    val fen: String,
)

/** Wire shape of the §3.3 200 responses — both the evaluated and the `unknown` rows. */
@Serializable
internal data class EvalResponseDto(
    val fen: String,
    @SerialName("eval_cp") val evalCp: Int? = null,
    val mate: Int? = null,
    @SerialName("best_move") val bestMove: String? = null,
    val depth: Int? = null,
    val source: String,
    @SerialName("fetched_at") val fetchedAt: String? = null,
)

@Serializable
private data class EvalErrorDto(
    val error: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Thin adapter over the `lichess-eval` Edge Function: invoke with `{fen}`, map the §3.3 response
 * table to [EvalOutcome]. 401 propagates (global auth gate, see [EvalRepository]); 400 propagates
 * too — the FEN comes from Position.toFen(), so `invalid_fen` is a programming error, not a state
 * the UI handles.
 */
class SupabaseEvalRepository(
    private val client: SupabaseClient,
) : EvalRepository {
    override suspend fun evaluate(fen: String): EvalOutcome =
        try {
            val response =
                client.functions.invoke(
                    function = "lichess-eval",
                    body = EvalRequestDto(fen),
                    // The Functions plugin does not set Content-Type for typed bodies itself.
                    headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        },
                )
            parseEvalSuccess(response.bodyAsText())
        } catch (e: RestException) {
            mapEvalError(e.statusCode, e.error) ?: throw e
        } catch (_: HttpRequestException) {
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null)
        } catch (_: HttpRequestTimeoutException) {
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null)
        }
}

/** Maps a §3.3 200 body to its outcome. Pure; throws on a body outside the contract. */
internal fun parseEvalSuccess(body: String): EvalOutcome {
    val dto = json.decodeFromString<EvalResponseDto>(body)
    if (dto.source == "unknown") return EvalOutcome.NoEval
    return EvalOutcome.Evaluated(
        evalCp = dto.evalCp,
        mate = dto.mate,
        bestMoveUci = dto.bestMove,
        source = dto.source,
        depth = dto.depth,
    )
}

/**
 * Maps the retryable §3.3 error rows — 429 (with its body's `retry_after_seconds`) and 5xx — to
 * [EvalOutcome.TemporarilyUnavailable]; null for anything else so the caller rethrows.
 */
internal fun mapEvalError(
    statusCode: Int,
    body: String,
): EvalOutcome? =
    when {
        statusCode == 429 -> {
            EvalOutcome.TemporarilyUnavailable(
                retryAfterSeconds =
                    runCatching {
                        json
                            .decodeFromString<EvalErrorDto>(
                                body,
                            ).retryAfterSeconds
                    }.getOrNull(),
            )
        }

        statusCode in 500..599 -> {
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null)
        }

        else -> {
            null
        }
    }
