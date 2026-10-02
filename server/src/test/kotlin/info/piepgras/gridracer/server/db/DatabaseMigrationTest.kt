package info.piepgras.gridracer.server.db

import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * The persisted format has readers on disk that this build did not write: a server upgraded in
 * place opens a database at whatever `user_version` the previous build left. Nothing else tests
 * that path - every other test starts from an empty in-memory database and lands on the current
 * schema in one go.
 */
class DatabaseMigrationTest {

    /** A connection that already holds the schema exactly as the first release wrote it. */
    private fun connectionAtVersionOne(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    public_id TEXT NOT NULL UNIQUE,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    rating INTEGER NOT NULL DEFAULT 1000,
                    matches INTEGER NOT NULL DEFAULT 0,
                    wins INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE UNIQUE INDEX idx_accounts_username ON accounts(username)")
            stmt.execute(
                """
                CREATE TABLE sessions (
                    token TEXT PRIMARY KEY,
                    account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX idx_sessions_account ON sessions(account_id)")
            stmt.execute("CREATE INDEX idx_sessions_expires ON sessions(expires_at)")
            stmt.execute(
                "INSERT INTO accounts (public_id, username, password_hash, display_name, rating, matches, wins, created_at) " +
                    "VALUES ('pub-1', 'veteran', 'hash', 'Veteran', 1234, 5, 2, 1700000000000)",
            )
            stmt.execute("PRAGMA user_version=1")
        }
        return conn
    }

    private fun userVersion(conn: Connection): Int =
        conn.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun columnsOf(conn: Connection, table: String): Set<String> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }

    @Test
    fun aVersionOneDatabaseIsMigratedToTheCurrentSchemaWithItsRowsIntact() = runBlocking {
        val conn = connectionAtVersionOne()
        val db = Database(conn)
        try {
            assertEquals(Database.SCHEMA_VERSION, userVersion(conn))
            // Every later migration has to have run: the v3 tables and the v4 columns exist.
            assertTrue("shared_tracks", columnsOf(conn, "shared_tracks").containsAll(setOf("draft_json", "rating_sum", "rating_count")))
            assertTrue("track_ratings", columnsOf(conn, "track_ratings").contains("rater_id"))
            assertTrue("track_reports", columnsOf(conn, "track_reports").contains("reason"))
            // ...and the data written by the old build is still readable through the DAO.
            val account = db.read { AccountDao().findByUsername(it, "veteran") }
            assertNotNull(account)
            assertEquals(1234, account!!.rating)
            assertEquals(5, account.matches)
        } finally {
            db.close()
        }
    }

    @Test
    fun aFreshDatabaseLandsOnTheSameVersionAsAMigratedOne() = runBlocking {
        val fresh = Database(":memory:")
        try {
            val version = fresh.read { userVersion(it) }
            assertEquals(Database.SCHEMA_VERSION, version)
            // Same shape both ways, so a migrated database and a fresh one never drift apart.
            val migratedConn = connectionAtVersionOne()
            val migrated = Database(migratedConn)
            try {
                assertEquals(
                    fresh.read { columnsOf(it, "shared_tracks") },
                    columnsOf(migratedConn, "shared_tracks"),
                )
            } finally {
                migrated.close()
            }
        } finally {
            fresh.close()
        }
    }

    @Test
    fun aDatabaseFromANewerBuildRefusesToOpen() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA user_version=${Database.SCHEMA_VERSION + 1}") }
        try {
            Database(conn)
            fail("a schema newer than this build must not be opened - downgrading silently corrupts it")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("newer"))
        }
    }
}
