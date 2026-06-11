package org.rurbaniak.smartchessboard.domain.chess

// Reduced smoke-check depths on the slower iOS-simulator target (plan Phase 5 §2); special-rule
// coverage on this target comes from the curated edge-case suite, which runs in full.
internal actual val startPositionPerftDepth: Int = 3
internal actual val kiwipetePerftDepth: Int = 2
