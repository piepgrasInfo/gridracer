package info.piepgras.gridracer.server.db

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Data access for the `shared_tracks` and `track_reports` tables.
 *
 * Shared tracks store the [info.piepgras.gridracer.engine.TrackDraft] as JSON and the
 * compiled [info.piepgras.gridracer.engine.Track] as JSON; the server rebuilds the track
 * from the draft on upload so the stored track is always server-authoritative. Reports
 * are recorded for operator review; takedown is manual.
 */
class TrackShareDao {

    /**
     * One row of `shared_tracks`, joined with the author's login name and the rating aggregate.
     *
     * @property id The row id; internal only.
     * @property publicId The opaque id clients use for the track.
     * @property name The name the track is listed under.
     * @property authorId The uploader's [Account.id].
     * @property authorName The uploader's display name at upload time.
     * @property draftJson The uploaded [info.piepgras.gridracer.engine.TrackDraft], as JSON.
     * @property trackJson The track the server built from that draft, as JSON.
     * @property uploadedAt When the track was uploaded, in epoch millis.
     */
    data class SharedTrackRow(
        val id: Long,
        val publicId: String,
        val name: String,
        val authorId: Long,
        val authorName: String,
        /** Author's login name, resolved via a LEFT JOIN to accounts. Empty if the account was deleted. */
        val authorUsername: String,
        val draftJson: String,
        val trackJson: String,
        val uploadedAt: Long,
        /** Sum of all 1..5 ratings; 0 when the ratings table does not exist or no ratings yet. */
        val ratingSum: Long,
        /** Number of ratings recorded; 0 when unrated. */
        val ratingCount: Long,
    )

    /**
     * One row of `track_reports`: a player flagging a shared track for an operator to review.
     *
     * @property id The row id.
     * @property trackId The reported track's [SharedTrackRow.id].
     * @property reporterId The reporting account's [Account.id].
     * @property reason The reporter's free-text reason.
     * @property reportedAt When the report was filed, in epoch millis.
     */
    data class ReportRow(
        val id: Long,
        val trackId: Long,
        val reporterId: Long,
        val reason: String,
        val reportedAt: Long,
    )

    /** Inserts a shared track. Returns the stored row. */
    fun insert(
        conn: Connection,
        publicId: String,
        name: String,
        authorId: Long,
        authorName: String,
        draftJson: String,
        trackJson: String,
        now: Long,
    ): SharedTrackRow {
        conn.prepareStatement(
            "INSERT INTO shared_tracks (public_id, name, author_id, author_name, draft_json, track_json, uploaded_at, rating_sum, rating_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
        ).use { ps ->
            ps.setString(1, publicId)
            ps.setString(2, name)
            ps.setLong(3, authorId)
            ps.setString(4, authorName)
            ps.setString(5, draftJson)
            ps.setString(6, trackJson)
            ps.setLong(7, now)
            ps.executeUpdate()
        }
        return findByPublicId(conn, publicId)!!
    }

    /**
     * Looks a shared track up by the opaque id clients know it by.
     *
     * @return The row, or null if no track has that id.
     */
    fun findByPublicId(conn: Connection, publicId: String): SharedTrackRow? =
        conn.prepareStatement(selectWithJoins + " WHERE s.public_id = ?").use { ps ->
            ps.setString(1, publicId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }

    /**
     * Looks a shared track up by its internal row id.
     *
     * @return The row, or null if no track has that id.
     */
    fun findById(conn: Connection, id: Long): SharedTrackRow? =
        conn.prepareStatement(selectWithJoins + " WHERE s.id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }

    /** One page of shared tracks, newest first. */
    fun page(conn: Connection, offset: Int, limit: Int): Pair<List<SharedTrackRow>, Int> {
        val total = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM shared_tracks").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        val rows = conn.prepareStatement(
            selectWithJoins + " ORDER BY s.uploaded_at DESC, s.public_id ASC LIMIT ? OFFSET ?",
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, MAX_PAGE_SIZE))
            ps.setInt(2, offset.coerceAtLeast(0))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<SharedTrackRow>()
                while (rs.next()) list += rs.toRow()
                list
            }
        }
        return rows to total
    }

    /** Counts tracks authored by [authorId], for the per-account upload quota. */
    fun countByAuthor(conn: Connection, authorId: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM shared_tracks WHERE author_id = ?").use { ps ->
            ps.setLong(1, authorId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /** Records a report. Returns the stored row. */
    fun insertReport(
        conn: Connection,
        trackId: Long,
        reporterId: Long,
        reason: String,
        now: Long,
    ): ReportRow {
        val publicId = UUID.randomUUID().toString()
        conn.prepareStatement(
            "INSERT INTO track_reports (public_id, track_id, reporter_id, reason, reported_at) " +
                "VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, publicId)
            ps.setLong(2, trackId)
            ps.setLong(3, reporterId)
            ps.setString(4, reason)
            ps.setLong(5, now)
            ps.executeUpdate()
        }
        conn.prepareStatement("SELECT * FROM track_reports WHERE public_id = ?").use { ps ->
            ps.setString(1, publicId)
            ps.executeQuery().use { rs -> return rs.toReportRow() }
        }
    }

    /**
     * Inserts or replaces [raterId]'s rating of [trackId] and refreshes the denormalized
     * `rating_sum`/`rating_count` columns on `shared_tracks` to match. Must run inside the
     * caller's write transaction so the upsert and the recompute are atomic.
     *
     * Returns the previous rating (0 if there was none), so the caller can report the rater's
     * own rating back without a second query.
     */
    fun upsertRating(conn: Connection, trackId: Long, raterId: Long, rating: Int, now: Long): Int {
        val previous = conn.prepareStatement(
            "SELECT rating FROM track_ratings WHERE track_id = ? AND rater_id = ?",
        ).use { ps ->
            ps.setLong(1, trackId)
            ps.setLong(2, raterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val publicId = UUID.randomUUID().toString()
        // ON CONFLICT(track_id, rater_id) DO UPDATE keeps the (track_id, rater_id) pair unique
        // while letting a re-rating replace the old value; the public_id is regenerated but is
        // never read back by anyone, so changing it is harmless.
        conn.prepareStatement(
            "INSERT INTO track_ratings (public_id, track_id, rater_id, rating, rated_at) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(track_id, rater_id) DO UPDATE SET rating = excluded.rating, rated_at = excluded.rated_at",
        ).use { ps ->
            ps.setString(1, publicId)
            ps.setLong(2, trackId)
            ps.setLong(3, raterId)
            ps.setInt(4, rating)
            ps.setLong(5, now)
            ps.executeUpdate()
        }
        recomputeRatingAggregate(conn, trackId)
        return previous
    }

    /**
     * Rewrites `rating_sum`/`rating_count` on [trackId] from the `track_ratings` rows that
     * currently exist. Must run inside the caller's write transaction.
     *
     * From scratch rather than incrementally: cheaper than the arithmetic it replaces and
     * immune to drift if a row was ever touched out of band - which is exactly what happens
     * when an account is deleted and `ON DELETE CASCADE` removes its ratings without anyone
     * telling the aggregate. Deleting a track updates 0 rows here, which is the right no-op.
     */
    fun recomputeRatingAggregate(conn: Connection, trackId: Long) {
        conn.prepareStatement(
            "UPDATE shared_tracks SET rating_sum = COALESCE((SELECT SUM(rating) FROM track_ratings WHERE track_id = ?), 0), " +
                "rating_count = COALESCE((SELECT COUNT(*) FROM track_ratings WHERE track_id = ?), 0) " +
                "WHERE id = ?",
        ).use { ps ->
            ps.setLong(1, trackId)
            ps.setLong(2, trackId)
            ps.setLong(3, trackId)
            ps.executeUpdate()
        }
    }

    /**
     * The ids of every track [raterId] has rated. Read *before* an account is deleted, so the
     * aggregates on those tracks can be recomputed once the cascade has taken the ratings.
     */
    fun ratedTrackIds(conn: Connection, raterId: Long): List<Long> =
        conn.prepareStatement("SELECT track_id FROM track_ratings WHERE rater_id = ?").use { ps ->
            ps.setLong(1, raterId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }

    /**
     * [raterId]'s ratings for a whole page of tracks, keyed by track row id.
     *
     * One query for the page rather than one per row: the list endpoint returns up to
     * [MAX_PAGE_SIZE] tracks, and a per-row lookup would turn one page into 100 round-trips on
     * the single database thread every other request also queues behind.
     */
    fun ratingsBy(conn: Connection, raterId: Long, trackIds: List<Long>): Map<Long, Int> {
        if (trackIds.isEmpty()) return emptyMap()
        // The id list comes from a page we just read, never from the client, so building the
        // IN list is safe - and the ids are bound as parameters regardless.
        val placeholders = trackIds.joinToString(",") { "?" }
        return conn.prepareStatement(
            "SELECT track_id, rating FROM track_ratings WHERE rater_id = ? AND track_id IN ($placeholders)",
        ).use { ps ->
            ps.setLong(1, raterId)
            trackIds.forEachIndexed { index, id -> ps.setLong(index + 2, id) }
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) }
            }
        }
    }

    /** Returns [raterId]'s current rating of [trackId], or null if they have not rated it. */
    fun findRating(conn: Connection, trackId: Long, raterId: Long): Int? =
        conn.prepareStatement(
            "SELECT rating FROM track_ratings WHERE track_id = ? AND rater_id = ?",
        ).use { ps ->
            ps.setLong(1, trackId)
            ps.setLong(2, raterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }

    private fun ResultSet.toRow() = SharedTrackRow(
        id = getLong("id"),
        publicId = getString("public_id"),
        name = getString("name"),
        authorId = getLong("author_id"),
        authorName = getString("author_name"),
        authorUsername = getString("author_username") ?: "",
        draftJson = getString("draft_json"),
        trackJson = getString("track_json"),
        uploadedAt = getLong("uploaded_at"),
        ratingSum = getLong("rating_sum"),
        ratingCount = getLong("rating_count"),
    )

    private fun ResultSet.toReportRow() = ReportRow(
        id = getLong("id"),
        trackId = getLong("track_id"),
        reporterId = getLong("reporter_id"),
        reason = getString("reason"),
        reportedAt = getLong("reported_at"),
    )

    companion object {
        /** Largest page [page] will return, whatever the caller asks for. */
        const val MAX_PAGE_SIZE = 100

        /**
         * The shared_tracks SELECT with the author-username and rating-aggregate joins baked
         * in. Aliased as `s` so callers just append `WHERE s.public_id = ?` etc.
         *
         * `author_username` is a LEFT JOIN so a track whose author account was deleted still
         * loads (with an empty username) rather than disappearing from the browser.
         */
        private const val selectWithJoins: String =
            "SELECT s.id, s.public_id, s.name, s.author_id, s.author_name, " +
                "a.username AS author_username, s.draft_json, s.track_json, s.uploaded_at, " +
                "s.rating_sum, s.rating_count " +
                "FROM shared_tracks s LEFT JOIN accounts a ON a.id = s.author_id"
    }
}
