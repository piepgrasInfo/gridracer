package info.piepgras.gridracer.server.db

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The SQLite database backing accounts, sessions and the leaderboard.
 *
 * One JDBC connection on one dedicated thread. SQLite serialises writers itself, so a pool
 * would only invent contention and "database is locked" errors; a single connection is both
 * the simplest and the correct shape for this server's load. WAL is enabled so a future read
 * path can run concurrently if needed, but every call here still goes through [dispatcher] to
 * keep the connection single-threaded.
 *
 * Migrations are driven by `PRAGMA user_version` and run in a transaction each. An unknown
 * version (a database written by a newer build than this one) fails fast rather than guessing
 * at a downgrade - starting the server against a schema it does not understand is a bug, not
 * a recovery scenario.
 */
class Database internal constructor(
    /**
     * An already-open connection. Internal so a migration test can hand in an in-memory
     * database it has first written an *older* schema into - an in-memory SQLite database is
     * private to its connection, so there is no other way to start one at an old version.
     */
    private val connection: Connection,
    /**
     * Override the dispatcher for tests. Production uses a single-thread executor so every
     * JDBC call lands on the same thread that owns the [Connection].
     */
    private val dispatcher: CoroutineDispatcher = singleThreadDispatcher("gridracer-db"),
) : AutoCloseable {

    constructor(
        path: String = "gridracer.sqlite",
        dispatcher: CoroutineDispatcher = singleThreadDispatcher("gridracer-db"),
    ) : this(
        // The SQLite JDBC driver registers itself with DriverManager on class load; the URL
        // is what selects the file. ":memory:" is supported and used by tests.
        DriverManager.getConnection("jdbc:sqlite:$path"),
        dispatcher,
    )

    private val log = LoggerFactory.getLogger(Database::class.java)

    init {
        // A single writer connection in WAL mode lets readers (if any are added later) see
        // committed data without blocking the writer.
        with(connection) {
            autoCommit = true
            createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("PRAGMA busy_timeout=5000")
            }
        }
        migrate()
    }

    /** Run a read-only [block] on the DB thread, returning its result. */
    suspend fun <T> read(block: (Connection) -> T): T = withContext(dispatcher) { block(connection) }

    /** Run a write [block] on the DB thread inside a transaction. */
    suspend fun <T> write(block: (Connection) -> T): T = withContext(dispatcher) {
        val conn = connection
        val wasAuto = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = wasAuto
        }
    }

    private fun migrate() {
        val currentVersion = connection.createStatement()
            .use { it.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        if (currentVersion > MIGRATIONS.size) {
            // A schema written by a build newer than this one. Downgrading silently would
            // corrupt data the new columns hold; refuse to start instead.
            throw IllegalStateException(
                "Database schema version $currentVersion is newer than this build supports (${MIGRATIONS.size}); refusing to start.",
            )
        }
        for (v in currentVersion until MIGRATIONS.size) {
            val migration = MIGRATIONS[v]
            log.info("Applying database migration to version {}", v + 1)
            connection.autoCommit = false
            try {
                migration.migrate(connection)
                connection.createStatement().use { it.execute("PRAGMA user_version=${v + 1}") }
                connection.commit()
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                throw IllegalStateException("Database migration to version ${v + 1} failed", t)
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun close() {
        runCatching { connection.close() }
        (dispatcher as? ExecutorCoroutineDispatcher)?.let { runCatching { it.close() } }
    }

    private fun interface Migration {
        fun migrate(conn: Connection)
    }

    companion object {
        /** The schema version a fresh database ends up at. Visible so a test can assert it. */
        val SCHEMA_VERSION: Int get() = MIGRATIONS.size

        /**
         * Ordered schema migrations. Index + 1 is the `user_version` each one produces.
         *
         * v1 creates accounts and sessions. The rating columns live on `accounts` from the
         * start so the Phase 5 leaderboard does not need a migration: only account-holding
         * players are rated, so a row here is the natural home for rating/matches/wins.
         *
         * v2 adds the leaderboard index on (rating DESC, public_id) so the page query does
         * not sort the whole table on every request.
         *
         * v3 creates the shared_tracks and track_reports tables for Phase 7 custom-map sharing.
         *
         * v4 adds per-account track ratings: a `track_ratings` table (one row per
         * account/track, so re-rating replaces) plus denormalized `rating_sum`/`rating_count`
         * columns on `shared_tracks` so the list query does not hit the ratings table per row.
         */
        private val MIGRATIONS: List<Migration> = listOf(
            Migration { conn ->
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
                }
            },
            Migration { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE INDEX idx_accounts_leaderboard ON accounts(rating DESC, public_id)")
                }
            },
            Migration { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE shared_tracks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            public_id TEXT NOT NULL UNIQUE,
                            name TEXT NOT NULL,
                            author_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            author_name TEXT NOT NULL,
                            draft_json TEXT NOT NULL,
                            track_json TEXT NOT NULL,
                            uploaded_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    stmt.execute("CREATE INDEX idx_shared_tracks_author ON shared_tracks(author_id)")
                    stmt.execute("CREATE INDEX idx_shared_tracks_uploaded ON shared_tracks(uploaded_at DESC)")
                    stmt.execute(
                        """
                        CREATE TABLE track_reports (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            public_id TEXT NOT NULL UNIQUE,
                            track_id INTEGER NOT NULL REFERENCES shared_tracks(id) ON DELETE CASCADE,
                            reporter_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            reason TEXT NOT NULL,
                            reported_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    stmt.execute("CREATE INDEX idx_track_reports_track ON track_reports(track_id)")
                }
            },
            Migration { conn ->
                conn.createStatement().use { stmt ->
                    // Denormalized aggregate columns on shared_tracks. Defaulted to 0 so
                    // existing rows are valid immediately; kept in sync by TrackShareDao
                    // inside the rating upsert transaction.
                    stmt.execute("ALTER TABLE shared_tracks ADD COLUMN rating_sum INTEGER NOT NULL DEFAULT 0")
                    stmt.execute("ALTER TABLE shared_tracks ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0")
                    stmt.execute(
                        """
                        CREATE TABLE track_ratings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            public_id TEXT NOT NULL UNIQUE,
                            track_id INTEGER NOT NULL REFERENCES shared_tracks(id) ON DELETE CASCADE,
                            rater_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            rating INTEGER NOT NULL,
                            rated_at INTEGER NOT NULL,
                            UNIQUE (track_id, rater_id)
                        )
                        """.trimIndent(),
                    )
                    // The (track_id, rater_id) UNIQUE already enforces one rating per pair;
                    // this index backs the lookup-by-rater used to show "your rating".
                    stmt.execute("CREATE INDEX idx_track_ratings_rater ON track_ratings(rater_id)")
                }
            },
        )

        /** A single-thread dispatcher named so threads are identifiable in logs. */
        fun singleThreadDispatcher(name: String): CoroutineDispatcher {
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, name).apply { isDaemon = true }
            }
            return executor.asCoroutineDispatcher()
        }
    }
}
