package org.rurbaniak.smartchessboard.data.games

import kotlinx.coroutines.test.runTest
import org.rurbaniak.smartchessboard.domain.games.GameMode
import org.rurbaniak.smartchessboard.domain.games.GameRecord
import org.rurbaniak.smartchessboard.domain.games.GameStatus
import org.rurbaniak.smartchessboard.presentation.FakeGamesRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The S-06 mode seam: `createGame` now carries a [GameMode], the Supabase repository serializes it to
 * the §3.2 column token, and a `"physical"` row reads back as [GameMode.PHYSICAL]. The real
 * [SupabaseGamesRepository.createGame] needs a live client, so the (de)serialization is proven directly
 * via the internal column mapping and the repository-contract threading via the fake.
 */
class CreateGameModeTest {
    @Test
    fun toModeColumnWritesTheSection32Token() {
        assertEquals("digital", GameMode.DIGITAL.toModeColumn())
        assertEquals("physical", GameMode.PHYSICAL.toModeColumn())
    }

    @Test
    fun parseModeReadsTheSection32Token() {
        assertEquals(GameMode.DIGITAL, parseMode("digital"))
        assertEquals(GameMode.PHYSICAL, parseMode("physical"))
    }

    @Test
    fun modeColumnRoundTripsBothWays() {
        for (mode in GameMode.entries) {
            assertEquals(mode, parseMode(mode.toModeColumn()))
        }
    }

    @Test
    fun createGamePhysicalProducesAPhysicalRecord() =
        runTest {
            val repository = FakeGamesRepository()
            // Stub a digital record on purpose: the created record must carry the *requested* mode,
            // proving the new `mode` argument threads through the repository contract.
            repository.createdGame =
                GameRecord(
                    id = "game-physical",
                    createdAt = "2026-06-19T10:00:00+00:00",
                    mode = GameMode.DIGITAL,
                    status = GameStatus.IN_PROGRESS,
                    result = null,
                    whiteLabel = "Alice",
                    blackLabel = "Bob",
                    pgn = "",
                )

            val created = repository.createGame("Alice", "Bob", GameMode.PHYSICAL)

            assertEquals(GameMode.PHYSICAL, created.mode)
            assertEquals(listOf(GameMode.PHYSICAL), repository.createModes)
        }
}
