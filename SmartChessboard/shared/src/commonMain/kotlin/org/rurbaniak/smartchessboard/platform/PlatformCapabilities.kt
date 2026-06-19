package org.rurbaniak.smartchessboard.platform

/**
 * Whether this target can drive a physical reed-switch board. The single per-platform truth behind
 * the project's "web is digital-only" rule (`lessons.md`): true on Android/iOS, false on web (WasmJS).
 *
 * Consumed as an **active** gate — the New-game mode picker offers Physical only when true, and
 * History routes an in-progress physical game to the physical screen only when true (else Replay).
 * The `BoardConnection` and `PhysicalPlayViewModel` are registered solely in the Android/iOS Koin
 * modules, so web has no way to reach physical play even though the route graph is shared in commonMain.
 */
expect val supportsPhysicalBoard: Boolean
