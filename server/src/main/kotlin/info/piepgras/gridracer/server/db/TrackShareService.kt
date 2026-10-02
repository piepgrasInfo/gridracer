package info.piepgras.gridracer.server.db

import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.TrackBuilder
import info.piepgras.gridracer.engine.TrackDraft
import info.piepgras.gridracer.engine.TrackValidator
import info.piepgras.gridracer.multiplayer.SharedTrackSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Server-side custom-track sharing.
 *
 * The security-critical design choice: the client sends a [TrackDraft], and the server
 * rebuilds the [Track] from it via [TrackBuilder.build]. A crafted `Track` whose walls
 * disagree with its draft would otherwise be a cheat vector (walls in different places
 * than the visible track geometry). The client's compiled track is never stored or
 * rebroadcast; only the server-rebuilt one is.
 *
 * Track building is O(n²) in wall edges, so it runs on a bounded dispatcher with a timeout
 * - never on the WebSocket read coroutine, to prevent a denial-of-service through a
 * pathological draft that takes seconds to build.
 */
class TrackShareService(
    private val db: Database,
    private val dao: TrackShareDao = TrackShareDao(),
    /** Bounded dispatcher for track building; defaults to [Dispatchers.Default]. */
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Timeout for track building in milliseconds. */
    private val buildTimeoutMs: Long = 5_000,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * This server's stable id, baked into every uploaded track's public id as a UUIDv5
     * namespace so a track uploaded here can never collide with one uploaded to another
     * server. Empty disables namespacing (tests that don't care get a random UUID).
     */
    private val serverId: String = "",
    /** Human-readable server name attached to summaries for authorship display. */
    private val serverName: String = "",
) {
    private val log = LoggerFactory.getLogger(TrackShareService::class.java)

    /** Result of an upload attempt. */
    sealed interface UploadResult {
        /**
         * The track was stored.
         *
         * @property summary The list entry for the new track.
         * @property track The track as the server rebuilt it from the draft.
         */
        data class Ok(val summary: SharedTrackSummary, val track: Track) : UploadResult
        /**
         * The upload was refused and nothing was stored.
         *
         * @property reason Why.
         */
        data class Rejected(val reason: Rejection) : UploadResult
    }

    /**
     * Every reason an upload can be refused.
     *
     * [INVALID_NAME] is an empty or overlong name ([MAX_NAME_LENGTH]); [INVALID_DRAFT] fails
     * [info.piepgras.gridracer.engine.TrackValidator]; [QUOTA_EXCEEDED] means the author already
     * has [MAX_TRACKS_PER_AUTHOR] tracks; [BUILD_FAILED] and [BUILD_TIMEOUT] are the rebuild
     * throwing or overrunning its time limit; [TRACK_TOO_LARGE] is a built track with more than
     * [MAX_STORED_WALLS] walls. [NOT_AUTHENTICATED] is for callers that check the account first.
     */
    enum class Rejection {
        NOT_AUTHENTICATED,
        INVALID_NAME,
        INVALID_DRAFT,
        BUILD_FAILED,
        BUILD_TIMEOUT,
        QUOTA_EXCEEDED,
        TRACK_TOO_LARGE,
    }

    /** Result of a download attempt. */
    sealed interface DownloadResult {
        /**
         * The track was found.
         *
         * @property summary Its list entry.
         * @property draft The editable source, so it can be opened in the editor.
         * @property track The server-built playable track.
         */
        data class Ok(val summary: SharedTrackSummary, val draft: TrackDraft, val track: Track) : DownloadResult
        /** No shared track has that id. */
        data object NotFound : DownloadResult
    }

    /**
     * Result of a list request.
     *
     * @property tracks This page of summaries, newest first.
     * @property offset Zero-based index of the first summary on this page.
     * @property total How many shared tracks there are in all.
     */
    data class ListResult(val tracks: List<SharedTrackSummary>, val offset: Int, val total: Int)

    /** Result of a report attempt. */
    sealed interface ReportResult {
        /**
         * The report was recorded for an operator to review.
         *
         * @property trackId The reported track's public id.
         */
        data class Ok(val trackId: String) : ReportResult
        /** No shared track has that id. */
        data object NotFound : ReportResult
        /** Reporting needs a logged-in account. */
        data object NotAuthenticated : ReportResult
    }

    /** Result of a rate attempt. */
    sealed interface RateResult {
        /**
         * The rating was recorded, replacing any earlier one by the same account.
         *
         * @property trackId The rated track's public id.
         * @property yourRating The rating just given, 1 to 5.
         * @property averageRating The new mean across all raters.
         * @property ratingCount How many accounts have rated the track.
         */
        data class Ok(val trackId: String, val yourRating: Int, val averageRating: Double, val ratingCount: Int) : RateResult
        /** No shared track has that id. */
        data object NotFound : RateResult
        /** Rating needs a logged-in account. */
        data object NotAuthenticated : RateResult
        /** The rating was outside [MIN_RATING]..[MAX_RATING]. */
        data object InvalidRating : RateResult
    }

    /**
     * Uploads a track. The [authorId] must be a valid account id; the caller (MessageRouter)
     * is responsible for ensuring the connection is authenticated before calling.
     *
     * [authorUsername] is the author's login name (distinct from the display [authorName]),
     * recorded on the summary so the info screen can show "server @ username (display name)".
     */
    suspend fun upload(
        name: String,
        draft: TrackDraft,
        authorId: Long,
        authorName: String,
        now: Long,
        authorUsername: String = "",
    ): UploadResult {
        // Validate the name.
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            return UploadResult.Rejected(Rejection.INVALID_NAME)
        }

        // Validate the draft before building: a draft that fails validation will not build.
        val validation = TrackValidator.validate(draft)
        if (!validation.isValid) {
            log.debug("Rejected track upload: draft validation failed")
            return UploadResult.Rejected(Rejection.INVALID_DRAFT)
        }

        // Check the per-author quota before building (building is expensive).
        val currentCount = db.read { conn -> dao.countByAuthor(conn, authorId) }
        if (currentCount >= MAX_TRACKS_PER_AUTHOR) {
            return UploadResult.Rejected(Rejection.QUOTA_EXCEEDED)
        }

        // Build the track on a bounded dispatcher with a timeout. This is the security-critical
        // step: the server rebuilds the track from the draft, discarding any client-supplied
        // compiled track.
        //
        // We capture the build result as a Pair so we can distinguish timeout (outer null from
        // withTimeoutOrNull) from a build failure (inner null from runCatching.getOrNull()).
        // A local sealed type would be cleaner but Kotlin does not allow local sealed interfaces.
        var buildFailed = false
        val builtTrack: Track? = withContext(buildDispatcher) {
            withTimeoutOrNull(buildTimeoutMs) {
                runCatching { TrackBuilder.build(trimmedName, draft) }
                    .onFailure {
                        log.warn("Track build failed for upload", it)
                        buildFailed = true
                    }
                    .getOrNull()
            }
        }

        val track = when {
            builtTrack == null && buildFailed ->
                return UploadResult.Rejected(Rejection.BUILD_FAILED)
            builtTrack == null ->
                return UploadResult.Rejected(Rejection.BUILD_TIMEOUT)
            else -> builtTrack
        }

        // Size-check the rebuilt track before storing it.
        if (track.walls.size > MAX_STORED_WALLS) {
            return UploadResult.Rejected(Rejection.TRACK_TOO_LARGE)
        }

        val publicId = newTrackPublicId(authorId)
        val draftJson = json.encodeToString(draft)
        val trackJson = json.encodeToString(track)

        val row = db.write { conn ->
            dao.insert(conn, publicId, trimmedName, authorId, authorName, draftJson, trackJson, now)
        }

        val summary = row.toSummary()
        return UploadResult.Ok(summary, track)
    }

    /**
     * A globally-unique public id for a new track.
     *
     * When [serverId] is set, this is a UUIDv5 of `"<serverId>:<authorId>:<nonce>"` - the
     * server id namespaces it across deployments, the author id namespaces it across users on
     * the same server, and the random nonce distinguishes two uploads by the same user. When
     * [serverId] is empty (tests that don't care), a plain random UUID is used instead.
     */
    private fun newTrackPublicId(authorId: Long): String {
        if (serverId.isBlank()) return UUID.randomUUID().toString()
        val nonce = UUID.randomUUID()
        val name = "$serverId:$authorId:$nonce"
        return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
    }

    /**
     * Lists shared tracks, newest first.
     *
     * [forAccountId] is the *requester*, used only to fill in their own rating on each row.
     * Passing null (a guest) simply leaves those null - browsing never needs an account.
     */
    suspend fun list(offset: Int, limit: Int, forAccountId: Long? = null): ListResult = db.read { conn ->
        val (rows, total) = dao.page(conn, offset, limit)
        val mine = forAccountId?.let { dao.ratingsBy(conn, it, rows.map { row -> row.id }) } ?: emptyMap()
        ListResult(
            tracks = rows.map { it.toSummary(mine[it.id]) },
            offset = offset.coerceAtLeast(0),
            total = total,
        )
    }

    /** Downloads a shared track by its public id. See [list] for [forAccountId]. */
    suspend fun download(trackId: String, forAccountId: Long? = null): DownloadResult = db.read { conn ->
        val row = dao.findByPublicId(conn, trackId) ?: return@read DownloadResult.NotFound
        val draft = json.decodeFromString<TrackDraft>(row.draftJson)
        val track = json.decodeFromString<Track>(row.trackJson)
        val mine = forAccountId?.let { dao.findRating(conn, row.id, it) }
        DownloadResult.Ok(row.toSummary(mine), draft, track)
    }

    /** Records a report against a shared track. */
    suspend fun report(trackId: String, reporterId: Long, reason: String, now: Long): ReportResult = db.write { conn ->
        val row = dao.findByPublicId(conn, trackId) ?: return@write ReportResult.NotFound
        dao.insertReport(conn, row.id, reporterId, reason.take(MAX_REPORT_REASON_LENGTH), now)
        ReportResult.Ok(trackId)
    }

    /**
     * Records or replaces [raterId]'s 1..5 [rating] of the track named by [trackId]. Returns
     * the new aggregate so the caller can echo it back without a follow-up read.
     */
    suspend fun rate(trackId: String, raterId: Long, rating: Int, now: Long): RateResult = db.write { conn ->
        if (rating !in MIN_RATING..MAX_RATING) return@write RateResult.InvalidRating
        val row = dao.findByPublicId(conn, trackId) ?: return@write RateResult.NotFound
        dao.upsertRating(conn, row.id, raterId, rating, now)
        val refreshed = dao.findByPublicId(conn, trackId)!!
        val count = refreshed.ratingCount.toInt()
        val average = if (count > 0) refreshed.ratingSum.toDouble() / count else 0.0
        RateResult.Ok(trackId, rating, average, count)
    }

    private fun TrackShareDao.SharedTrackRow.toSummary(yourRating: Int? = null) = SharedTrackSummary(
        trackId = publicId,
        name = name,
        authorName = authorName,
        uploadedAt = uploadedAt,
        authorUsername = authorUsername,
        serverId = serverId,
        serverName = serverName,
        ratingCount = ratingCount.toInt(),
        averageRating = if (ratingCount > 0) ratingSum.toDouble() / ratingCount else 0.0,
        yourRating = yourRating,
    )

    companion object {
        /** Longest track name, in characters, after trimming. */
        const val MAX_NAME_LENGTH = 64
        /** Most shared tracks one account may have stored. */
        const val MAX_TRACKS_PER_AUTHOR = 50
        /** Most walls a rebuilt track may have to be stored. */
        const val MAX_STORED_WALLS = 4000
        /** A report's reason is truncated to this many characters, not refused. */
        const val MAX_REPORT_REASON_LENGTH = 500
        /** Lowest rating a player may give. */
        const val MIN_RATING = 1
        /** Highest rating a player may give. */
        const val MAX_RATING = 5
    }
}
