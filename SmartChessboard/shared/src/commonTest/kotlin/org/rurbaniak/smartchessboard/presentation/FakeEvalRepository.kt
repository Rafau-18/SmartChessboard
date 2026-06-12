package org.rurbaniak.smartchessboard.presentation

import org.rurbaniak.smartchessboard.domain.eval.EvalOutcome
import org.rurbaniak.smartchessboard.domain.eval.EvalRepository

/**
 * Scripted fake for the analysis machine: per-FEN outcomes with a default, an invocation counter
 * (cache assertions), and a suspend hook so a test can hold a request in flight (cancellation
 * scenarios).
 */
class FakeEvalRepository : EvalRepository {
    var outcomes: Map<String, EvalOutcome> = emptyMap()
    var defaultOutcome: EvalOutcome = EvalOutcome.NoEval
    var evaluateCalls = 0
    val requestedFens = mutableListOf<String>()

    /** Runs before each answer; install a `delay`/latch here to keep the request in flight. */
    var onEvaluate: suspend (String) -> Unit = {}

    override suspend fun evaluate(fen: String): EvalOutcome {
        evaluateCalls++
        requestedFens += fen
        onEvaluate(fen)
        return outcomes[fen] ?: defaultOutcome
    }
}
