package org.rurbaniak.smartchessboard.domain.eval

/** The three user-meaningful outcomes of evaluating a position (contract §3.3). */
sealed interface EvalOutcome {
    /** The shared cache or a provider returned an evaluation. */
    data class Evaluated(
        /** Centipawn score, White POV; null when the position is a forced mate. */
        val evalCp: Int?,
        /** Forced-mate distance in moves, White-POV signed (negative = Black mates); null unless forced. */
        val mate: Int?,
        /** Best move in UCI notation, e.g. "e2e4". */
        val bestMoveUci: String?,
        /** The provider that produced the eval ("lichess" / "chess-api") — survives cache hits. */
        val source: String,
        val depth: Int?,
        /** True when the response was served from the shared cache rather than a live provider call. */
        val cached: Boolean = false,
    ) : EvalOutcome

    /** No provider knows this position (`source: "unknown"`) — a stable answer, not an error. */
    data object NoEval : EvalOutcome

    /** Rate limit, upstream outage, or network failure — worth retrying. */
    data class TemporarilyUnavailable(
        val retryAfterSeconds: Int?,
    ) : EvalOutcome
}
