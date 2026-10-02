package info.piepgras.gridracer.server.db

import java.sql.ResultSet
import java.util.UUID

/**
 * Data access for the `accounts` and `sessions` tables.
 *
 * Every method runs on the [Database] thread via the `read`/`write` wrappers in the calling
 * service - this object holds no concurrency state of its own and is safe to call from any
 * coroutine that has already dispatched onto the DB thread.
 */
class AccountDao {

    /** Inserts a new account. Returns the stored row, or null if the username is taken. */
    fun insert(
        conn: java.sql.Connection,
        publicId: String,
        username: String,
        passwordHash: String,
        displayName: String,
        now: Long,
    ): Account? {
        conn.prepareStatement(
            "INSERT INTO accounts (public_id, username, password_hash, display_name, rating, matches, wins, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, publicId)
            ps.setString(2, username)
            ps.setString(3, passwordHash)
            ps.setString(4, displayName)
            ps.setInt(5, STARTING_RATING)
            ps.setLong(6, now)
            try {
                ps.executeUpdate()
            } catch (_: java.sql.SQLException) {
                // UNIQUE violation on username or public_id. public_id is a fresh UUID so a
                // collision is astronomically unlikely; a username clash is the real case.
                return null
            }
        }
        return findByUsername(conn, username)
    }

    /**
     * Looks an account up by its internal row id.
     *
     * @return The account, or null if there is none.
     */
    fun findById(conn: java.sql.Connection, id: Long): Account? =
        conn.prepareStatement("SELECT * FROM accounts WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
        }

    /**
     * Deletes the account row. Returns true if a row was removed.
     *
     * Everything else the account owns goes with it through `ON DELETE CASCADE` - sessions,
     * tracks it authored (and those tracks' ratings and reports), and the ratings and reports
     * it filed on other people's tracks. `PRAGMA foreign_keys=ON` is set in [Database]; without
     * it SQLite would ignore the cascades and leave the orphans behind.
     *
     * What the cascade cannot do is fix the denormalized `rating_sum`/`rating_count` columns on
     * the tracks this account had rated - see `TrackShareDao.recomputeRatingAggregate`, which
     * the caller must run in the same transaction.
     */
    fun deleteAccount(conn: java.sql.Connection, id: Long): Boolean =
        conn.prepareStatement("DELETE FROM accounts WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate() > 0
        }

    /**
     * Looks an account up by login name, ignoring case - "Alice" and "alice" are one account.
     *
     * @return The account, or null if there is none.
     */
    fun findByUsername(conn: java.sql.Connection, username: String): Account? =
        conn.prepareStatement("SELECT * FROM accounts WHERE username = ? COLLATE NOCASE").use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
        }

    // ---- Sessions ---------------------------------------------------------------------

    /** Inserts a session row. Replaces any existing row for the same token (PK). */
    fun insertSession(conn: java.sql.Connection, token: String, accountId: Long, now: Long, expiresAt: Long) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sessions (token, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, token)
            ps.setLong(2, accountId)
            ps.setLong(3, now)
            ps.setLong(4, expiresAt)
            ps.executeUpdate()
        }
    }

    /** Returns a non-expired session for [token], or null. */
    fun findSession(conn: java.sql.Connection, token: String, now: Long): StoredSession? =
        conn.prepareStatement("SELECT * FROM sessions WHERE token = ? AND expires_at > ?").use { ps ->
            ps.setString(1, token)
            ps.setLong(2, now)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
        }

    /** Deletes the session with [token], logging that device out. Does nothing if there is none. */
    fun deleteSession(conn: java.sql.Connection, token: String) {
        conn.prepareStatement("DELETE FROM sessions WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeUpdate()
        }
    }

    /** Removes all sessions that have expired. Called periodically by the cleanup loop. */
    fun deleteExpiredSessions(conn: java.sql.Connection, now: Long): Int =
        conn.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }

    private fun ResultSet.toAccount(): Account = Account(
        id = getLong("id"),
        publicId = getString("public_id"),
        username = getString("username"),
        passwordHash = getString("password_hash"),
        displayName = getString("display_name"),
        rating = getInt("rating"),
        matches = getInt("matches"),
        wins = getInt("wins"),
    )

    private fun ResultSet.toSession(): StoredSession = StoredSession(
        token = getString("token"),
        accountId = getLong("account_id"),
        expiresAt = getLong("expires_at"),
    )

    companion object {
        /** The Elo rating a new account starts at. */
        const val STARTING_RATING = 1000
        /** A fresh random UUID for [Account.publicId], unrelated to any secret. */
        fun newPublicId(): String = UUID.randomUUID().toString()
    }
}
