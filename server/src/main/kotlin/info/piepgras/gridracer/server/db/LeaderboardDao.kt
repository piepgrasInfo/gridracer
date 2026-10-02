package info.piepgras.gridracer.server.db

import java.sql.Connection
import java.sql.ResultSet

/**
 * Data access for the leaderboard view over the `accounts` table.
 *
 * The leaderboard is just a window over `accounts` ordered by rating; there is no separate
 * table because only account-holding players are rated (Phase 5). A guest or AI never has a
 * row here, which is exactly the "account-holding subset" the plan calls for.
 */
class LeaderboardDao {

    /** One page of the leaderboard, highest rating first. */
    fun page(
        conn: Connection,
        offset: Int,
        limit: Int,
        /** Account id of the caller, so their own row can be returned alongside the page. */
        forAccountId: Long?,
    ): LeaderboardPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val total = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM accounts").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val entries = conn.prepareStatement(
            "SELECT public_id, display_name, rating, matches, wins FROM accounts " +
                "ORDER BY rating DESC, public_id ASC LIMIT ? OFFSET ?",
        ).use { ps ->
            ps.setInt(1, safeLimit)
            ps.setInt(2, safeOffset)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<LeaderboardEntry>()
                var rank = safeOffset + 1
                while (rs.next()) {
                    list += rs.toEntry(rank)
                    rank++
                }
                list
            }
        }
        val you = forAccountId?.let { accountId(conn, it) }
        return LeaderboardPage(entries = entries, offset = safeOffset, totalEntries = total, you = you)
    }

    /** The caller's own row, with its absolute rank, or null if they have no account row. */
    private fun accountId(conn: Connection, accountId: Long): LeaderboardEntry? {
        // Read the account row first, extracting values before the ResultSet closes.
        val row = conn.prepareStatement(
            "SELECT public_id, display_name, rating, matches, wins FROM accounts WHERE id = ?",
        ).use { ps ->
            ps.setLong(1, accountId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                AccountRow(
                    publicId = rs.getString("public_id"),
                    displayName = rs.getString("display_name"),
                    rating = rs.getInt("rating"),
                    matches = rs.getInt("matches"),
                    wins = rs.getInt("wins"),
                )
            }
        }
        val rank = conn.prepareStatement(
            "SELECT COUNT(*) FROM accounts WHERE rating > ? OR (rating = ? AND public_id < ?)",
        ).use { ps ->
            ps.setInt(1, row.rating)
            ps.setInt(2, row.rating)
            ps.setString(3, row.publicId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } + 1
        }
        return LeaderboardEntry(
            publicId = row.publicId,
            displayName = row.displayName,
            rating = row.rating,
            matches = row.matches,
            wins = row.wins,
            rank = rank,
        )
    }

    private data class AccountRow(
        val publicId: String,
        val displayName: String,
        val rating: Int,
        val matches: Int,
        val wins: Int,
    )

    /**
     * Applies a rating update for a set of accounts atomically.
     *
     * [deltas] is keyed by account id; [wins] is the set of account ids that won (finish rank 1).
     * Each affected account's rating, matches and wins are updated in one statement.
     */
    fun applyRaceResult(
        conn: Connection,
        deltas: Map<Long, Int>,
        winners: Set<Long>,
    ) {
        if (deltas.isEmpty()) return
        conn.prepareStatement(
            "UPDATE accounts SET rating = MAX(0, rating + ?), matches = matches + 1, " +
                "wins = wins + ? WHERE id = ?",
        ).use { ps ->
            for ((accountId, delta) in deltas) {
                ps.setInt(1, delta)
                ps.setInt(2, if (accountId in winners) 1 else 0)
                ps.setLong(3, accountId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun ResultSet.toEntry(rank: Int) = LeaderboardEntry(
        publicId = getString("public_id"),
        displayName = getString("display_name"),
        rating = getInt("rating"),
        matches = getInt("matches"),
        wins = getInt("wins"),
        rank = rank,
    )

    companion object {
        /** Largest page [page] will return, whatever the caller asks for. */
        const val MAX_PAGE_SIZE = 100
    }
}

/** One row of the leaderboard. Mirrors the wire [info.piepgras.gridracer.multiplayer.RatingEntry]. */
data class LeaderboardEntry(
    /** Opaque, stable handle for the player; never accepted as authentication. */
    val publicId: String,
    /** The player's display name. */
    val displayName: String,
    /** Current Elo rating. */
    val rating: Int,
    /** Rated races played. */
    val matches: Int,
    /** Rated races won. */
    val wins: Int,
    /** 1-based position on the leaderboard. */
    val rank: Int,
)

/** A slice of the leaderboard plus the requesting player's own row when they have one. */
data class LeaderboardPage(
    /** The rows in this slice, highest rating first. */
    val entries: List<LeaderboardEntry>,
    /** Zero-based leaderboard position of the first row. */
    val offset: Int,
    /** How many accounts the leaderboard holds in all. */
    val totalEntries: Int,
    /** The requesting account's own row, or null for a guest or when not asked for. */
    val you: LeaderboardEntry?,
)
