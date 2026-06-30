package org.rurbaniak.smartchessboard.domain.board

/**
 * Persists the id of the last board the user successfully connected to, so the connection screen
 * (S-09 Phase 5) can auto-connect on next entry instead of re-scanning every time. The id is a
 * [DiscoveredBoard.id] (the Kable peripheral identifier) — opaque, only ever fed back to
 * [BoardTransport.connect]. Mobile-only by use (the connection screen is never reached on web), though
 * the implementation is platform-agnostic over multiplatform-settings.
 */
interface RememberedBoardStore {
    /** The remembered board id, or null if none has been paired yet (or it was forgotten). */
    fun rememberedId(): String?

    /** Record [id] as the remembered board after a successful connect. Overwrites any previous value. */
    fun remember(id: String)

    /** Clear the remembered board (the "forget device" affordance) so the next entry re-scans. */
    fun forget()
}
