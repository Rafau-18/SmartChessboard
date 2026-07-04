package org.rurbaniak.smartchessboard.domain.chess.pgn

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the FR-022 fixture-parity contract: every PGN inlined in the seed-on-signup
 * migration (`supabase/migrations/<ts>_seed_sample_games_on_signup.sql`) must stay
 * byte-identical to its [PgnFixtures] source of truth — parity with the parser-verified
 * fixtures is what makes the seeded games provably replayable. JVM-only: reads the
 * migration from disk, so it runs on `:shared:testAndroidHostTest` and nowhere else.
 */
class SeedPgnParityTest {
    @Test
    fun seedMigrationPgnsAreByteIdenticalToTheFixtures() {
        val migration = findSeedMigration()
        // $pgn$…$pgn$ dollar-quoted literals sit at the odd indices of a split on the delimiter.
        val parts = migration.readText().split("\$pgn\$")
        assertTrue(parts.size % 2 == 1, "unbalanced \$pgn\$ delimiters in ${migration.name}")
        val blocks = parts.filterIndexed { index, _ -> index % 2 == 1 }

        val fixtures =
            mapOf(
                "OPERA_GAME" to PgnFixtures.OPERA_GAME,
                "IMMORTAL_GAME" to PgnFixtures.IMMORTAL_GAME,
                "EVERGREEN_GAME" to PgnFixtures.EVERGREEN_GAME,
                "GAME_OF_THE_CENTURY" to PgnFixtures.GAME_OF_THE_CENTURY,
                "KASPAROV_IMMORTAL" to PgnFixtures.KASPAROV_IMMORTAL,
                "POLGAR_KASPAROV" to PgnFixtures.POLGAR_KASPAROV,
                "DEEP_BLUE_KASPAROV" to PgnFixtures.DEEP_BLUE_KASPAROV,
                "GOLD_COINS_GAME" to PgnFixtures.GOLD_COINS_GAME,
            )

        assertEquals(
            fixtures.size,
            blocks.size,
            "migration ${migration.name} must inline exactly ${fixtures.size} \$pgn\$ blocks",
        )
        assertEquals(
            emptyList(),
            blocks.filter { it !in fixtures.values },
            "migration PGN blocks that match no fixture byte-for-byte",
        )
        assertEquals(
            emptyList(),
            fixtures.filterValues { it !in blocks }.keys.toList(),
            "fixtures missing from the migration",
        )
    }

    private fun findSeedMigration(): File {
        val migrationsDir = File(findRepoRoot(), "supabase/migrations")
        return migrationsDir
            .listFiles { f -> f.name.endsWith("_seed_sample_games_on_signup.sql") }
            ?.singleOrNull()
            ?: fail("expected exactly one *_seed_sample_games_on_signup.sql in $migrationsDir")
    }

    /** The test's working directory is the Gradle module, not the repo root — walk up. */
    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "supabase/migrations").isDirectory) return dir
            dir = dir.parentFile
        }
        fail("no supabase/migrations directory above ${System.getProperty("user.dir")}")
    }
}
