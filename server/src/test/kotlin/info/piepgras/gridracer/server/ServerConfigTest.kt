package info.piepgras.gridracer.server

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    private fun config(vararg env: Pair<String, String>) =
        ServerConfig.fromEnv(env.toMap()::get)

    @Test
    fun defaultsWhenNothingIsSet() {
        val config = config()

        assertEquals(ServerConfig.DEFAULT_HOST, config.host)
        assertEquals(ServerConfig.DEFAULT_PORT, config.port)
        assertEquals(ServerConfig.DEFAULT_SERVER_NAME, config.serverName)
        // The default server id is a UUID derived from the default database path, so it is
        // stable across calls with the same env.
        assertTrue(config.serverId.isNotBlank())
        assertEquals(config("GRIDRACER_DB_PATH" to "gridracer.sqlite").serverId, config().serverId)
    }

    @Test
    fun readsHostAndPort() {
        val config = config("HOST" to "127.0.0.1", "PORT" to "9000")

        assertEquals("127.0.0.1", config.host)
        assertEquals(9000, config.port)
    }

    @Test
    fun aMalformedPortFallsBackRatherThanFailingToBoot() {
        assertEquals(ServerConfig.DEFAULT_PORT, config("PORT" to "not-a-number").port)
        assertEquals(ServerConfig.DEFAULT_PORT, config("PORT" to "").port)
        assertEquals(ServerConfig.DEFAULT_PORT, config("PORT" to "0").port)
        assertEquals(ServerConfig.DEFAULT_PORT, config("PORT" to "70000").port)
        assertEquals(ServerConfig.DEFAULT_PORT, config("PORT" to "-1").port)
    }

    @Test
    fun aBlankHostFallsBackToTheDefault() {
        assertEquals(ServerConfig.DEFAULT_HOST, config("HOST" to "   ").host)
    }

    @Test
    fun serverIdIsReadFromEnvWhenProvided() {
        val id = "11111111-1111-1111-1111-111111111111"
        assertEquals(id, config("GRIDRACER_SERVER_ID" to id).serverId)
    }

    @Test
    fun aBlankServerIdFallsBackToTheDerivedDefault() {
        val derived = config("GRIDRACER_DB_PATH" to "/var/data/gridracer.sqlite").serverId
        assertEquals(derived, config("GRIDRACER_SERVER_ID" to "  ", "GRIDRACER_DB_PATH" to "/var/data/gridracer.sqlite").serverId)
    }

    @Test
    fun inMemoryDatabaseGetsAFreshRandomServerId() {
        // ":memory:" must not derive from a file path; it gets a fresh random UUID per call,
        // so two in-memory configs differ (tests should set GRIDRACER_SERVER_ID if they care).
        assertNotEquals(config("GRIDRACER_DB_PATH" to ":memory:").serverId, config("GRIDRACER_DB_PATH" to ":memory:").serverId)
    }

    @Test
    fun derivedServerIdIsAValidUuid() {
        // Throws if the derived id is not a UUID, which would break UUIDv5 namespacing upstream.
        UUID.fromString(config("GRIDRACER_DB_PATH" to "/var/data/gridracer.sqlite").serverId)
    }

    @Test
    fun serverNameIsReadFromEnvWhenProvided() {
        assertEquals("My Server", config("GRIDRACER_SERVER_NAME" to "My Server").serverName)
    }

    // -----------------------------------------------------------------------------------
    // Race logging
    // -----------------------------------------------------------------------------------

    @Test
    fun raceLoggingIsOnByDefault() {
        // A race nobody recorded cannot be settled afterwards, so the default is to record.
        val defaults = config()
        assertTrue(defaults.raceLogEnabled)
        assertEquals(ServerConfig.DEFAULT_RACE_LOG_DIR, defaults.raceLogDirectory)
        assertEquals(ServerConfig.DEFAULT_RACE_LOG_RETENTION_DAYS, defaults.raceLogRetentionDays)
    }

    @Test
    fun raceLoggingIsTurnedOffByAnyOfTheUsualOffValues() {
        for (off in listOf("off", "OFF", "false", "0", "no", "disabled", " off ")) {
            assertFalse("'$off' should disable race logging", config("GRIDRACER_RACE_LOG" to off).raceLogEnabled)
        }
    }

    @Test
    fun aTypoInTheRaceLogVariableLeavesLoggingOn() {
        // Opt-out, not opt-in: a misspelled value must not silently stop recording races.
        assertTrue(config("GRIDRACER_RACE_LOG" to "of").raceLogEnabled)
        assertTrue(config("GRIDRACER_RACE_LOG" to "on").raceLogEnabled)
        assertTrue(config("GRIDRACER_RACE_LOG" to "").raceLogEnabled)
    }

    @Test
    fun theRaceLogDirectoryAndRetentionAreReadFromEnv() {
        val custom = config(
            "GRIDRACER_RACE_LOG_DIR" to "/var/lib/gridracer/races",
            "GRIDRACER_RACE_LOG_RETENTION_DAYS" to "90",
        )
        assertEquals("/var/lib/gridracer/races", custom.raceLogDirectory)
        assertEquals(90L, custom.raceLogRetentionDays)
    }

    @Test
    fun aMalformedRetentionFallsBackToTheDefaultRatherThanToKeepingForever() {
        // Falling back to 0 would mean unbounded growth of personal data on a typo, which is
        // the worse failure of the two.
        for (bad in listOf("ninety", "-1", "", "  ")) {
            assertEquals(
                "'$bad' should fall back to the default retention",
                ServerConfig.DEFAULT_RACE_LOG_RETENTION_DAYS,
                config("GRIDRACER_RACE_LOG_RETENTION_DAYS" to bad).raceLogRetentionDays,
            )
        }
    }

    @Test
    fun anExplicitZeroRetentionDisablesTheSweep() {
        // Deliberate "keep everything" is allowed; it just has to be said out loud.
        assertEquals(0L, config("GRIDRACER_RACE_LOG_RETENTION_DAYS" to "0").raceLogRetentionDays)
    }
}
