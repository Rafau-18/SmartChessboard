package org.rurbaniak.smartchessboard.domain.eval

/**
 * Domain boundary for post-game position evaluations: resolves a faithful FEN (Position.toFen)
 * against the §3.3 eval chain. Auth failures (401) throw instead of mapping to an outcome —
 * session expiry is handled by the global auth gate, not per-screen.
 */
interface EvalRepository {
    suspend fun evaluate(fen: String): EvalOutcome
}
