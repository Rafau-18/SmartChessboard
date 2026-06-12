package org.rurbaniak.smartchessboard.data.eval

import org.rurbaniak.smartchessboard.domain.eval.EvalOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

class EvalResponseMappingTest {
    // --- 200 bodies → outcomes ---

    @Test
    fun evaluatedBodyMapsAllFields() {
        val body =
            """
            {"fen":"$START_FEN","eval_cp":22,"mate":null,"best_move":"e2e4","depth":40,
             "source":"lichess","fetched_at":"2026-06-12T10:00:00Z"}
            """.trimIndent()
        assertEquals(
            EvalOutcome.Evaluated(evalCp = 22, mate = null, bestMoveUci = "e2e4", source = "lichess", depth = 40),
            parseEvalSuccess(body),
        )
    }

    @Test
    fun forcedMateBodyKeepsTheWhitePovSign() {
        val body = """{"fen":"$START_FEN","eval_cp":null,"mate":-2,"best_move":"d8h4","depth":18,"source":"cache"}"""
        assertEquals(
            EvalOutcome.Evaluated(evalCp = null, mate = -2, bestMoveUci = "d8h4", source = "cache", depth = 18),
            parseEvalSuccess(body),
        )
    }

    @Test
    fun unknownSourceMapsToNoEval() {
        assertEquals(EvalOutcome.NoEval, parseEvalSuccess("""{"fen":"$START_FEN","source":"unknown"}"""))
    }

    @Test
    fun unknownKeysInTheBodyAreIgnored() {
        val body = """{"fen":"$START_FEN","source":"unknown","added_later":true}"""
        assertEquals(EvalOutcome.NoEval, parseEvalSuccess(body))
    }

    // --- error rows → outcomes ---

    @Test
    fun rateLimitedMapsTheBodysRetryAfterSeconds() {
        assertEquals(
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = 60),
            mapEvalError(429, """{"error":"rate_limited","retry_after_seconds":60}"""),
        )
    }

    @Test
    fun rateLimitedWithUnparsableBodyStillMapsWithoutRetryHint() {
        assertEquals(
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null),
            mapEvalError(429, "not json"),
        )
    }

    @Test
    fun upstreamUnavailableMapsToTemporarilyUnavailable() {
        assertEquals(
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null),
            mapEvalError(502, """{"error":"upstream_unavailable"}"""),
        )
    }

    @Test
    fun internalServerErrorMapsToTemporarilyUnavailable() {
        assertEquals(
            EvalOutcome.TemporarilyUnavailable(retryAfterSeconds = null),
            mapEvalError(500, """{"error":"internal"}"""),
        )
    }

    @Test
    fun authAndClientErrorsAreNotAbsorbed() {
        assertNull(mapEvalError(401, """{"error":"unauthenticated"}"""))
        assertNull(mapEvalError(400, """{"error":"invalid_fen"}"""))
    }
}
