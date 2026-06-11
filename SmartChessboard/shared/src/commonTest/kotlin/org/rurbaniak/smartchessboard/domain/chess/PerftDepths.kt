package org.rurbaniak.smartchessboard.domain.chess

// Per-target perft depth budget — a choice recorded up front (plan Phase 5 §2), not a reactive
// "if slow" truncation. The JVM host runs the full reference depths; the slower iOS-simulator and
// WasmJS targets run one depth less as a smoke check. Cross-target coverage of the special rules
// rests on the curated edge-case suite (ChessRulesTest), which runs in full on all three targets.

/** Start-position perft depth: 4 on the JVM host (197 281 nodes), 3 elsewhere (8 902 nodes). */
internal expect val startPositionPerftDepth: Int

/** Kiwipete perft depth: 3 on the JVM host (97 862 nodes), 2 elsewhere (2 039 nodes). */
internal expect val kiwipetePerftDepth: Int
