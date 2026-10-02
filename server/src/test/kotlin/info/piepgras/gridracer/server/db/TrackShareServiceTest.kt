package info.piepgras.gridracer.server.db

import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.TrackDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackShareServiceTest {

    private lateinit var db: Database
    private lateinit var service: TrackShareService
    private lateinit var auth: AuthService

    private fun validDraft(): TrackDraft = TrackDraft(
        outerBoundary = listOf(Point(0, 0), Point(20, 0), Point(20, 12), Point(0, 12)),
        innerBoundary = listOf(Point(7, 4), Point(13, 4), Point(13, 7), Point(7, 7)),
        finishLineFrom = Point(10, 7),
        finishLineTo = Point(10, 12),
        finishDirection = Point(-1, 0),
    )

    @Before
    fun setUp() {
        db = Database(":memory:")
        auth = AuthService(db)
        service = TrackShareService(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun registerAccount(username: String = "uploader", displayName: String = "Uploader"): Long {
        val result = auth.register(username, "password1", displayName) as AuthService.Result.Ok
        return result.success.account.id
    }

    @Test
    fun uploadRebuildsTrackFromDraftAndStoresIt() = runBlocking {
        val authorId = registerAccount()
        val result = service.upload("My Track", validDraft(), authorId, "Uploader", now = 1000L)

        assertTrue(result is TrackShareService.UploadResult.Ok)
        val ok = result as TrackShareService.UploadResult.Ok
        assertEquals("My Track", ok.summary.name)
        assertEquals("Uploader", ok.summary.authorName)
        assertEquals(1000L, ok.summary.uploadedAt)
        // The server-rebuilt track is returned.
        assertNotNull(ok.track)
        assertTrue(ok.track.walls.isNotEmpty())
    }

    @Test
    fun downloadReturnsStoredDraftAndTrack() = runBlocking {
        val authorId = registerAccount()
        val upload = service.upload("Downloadable", validDraft(), authorId, "Uploader", now = 1000L)
        assertTrue(upload is TrackShareService.UploadResult.Ok)
        val trackId = (upload as TrackShareService.UploadResult.Ok).summary.trackId

        val download = service.download(trackId)
        assertTrue(download is TrackShareService.DownloadResult.Ok)
        val ok = download as TrackShareService.DownloadResult.Ok
        assertEquals("Downloadable", ok.summary.name)
        // The downloaded draft matches what was uploaded.
        assertEquals(validDraft().outerBoundary, ok.draft.outerBoundary)
        assertEquals(validDraft().innerBoundary, ok.draft.innerBoundary)
        // The downloaded track is the server-rebuilt one.
        assertNotNull(ok.track)
    }

    @Test
    fun downloadReturnsNotFoundForUnknownId() = runBlocking {
        val result = service.download("does-not-exist")
        assertEquals(TrackShareService.DownloadResult.NotFound, result)
    }

    @Test
    fun listReturnsTracksNewestFirstWithTotal() = runBlocking {
        val authorId = registerAccount()
        service.upload("Old", validDraft(), authorId, "Uploader", now = 1000L)
        service.upload("New", validDraft(), authorId, "Uploader", now = 2000L)

        val page = service.list(offset = 0, limit = 10)
        assertEquals(2, page.total)
        assertEquals(2, page.tracks.size)
        // Newest first.
        assertEquals("New", page.tracks[0].name)
        assertEquals("Old", page.tracks[1].name)
    }

    @Test
    fun listRespectsOffsetAndLimit() = runBlocking {
        val authorId = registerAccount()
        service.upload("T1", validDraft(), authorId, "Uploader", now = 1000L)
        service.upload("T2", validDraft(), authorId, "Uploader", now = 2000L)
        service.upload("T3", validDraft(), authorId, "Uploader", now = 3000L)

        val page = service.list(offset = 1, limit = 1)
        assertEquals(3, page.total)
        assertEquals(1, page.tracks.size)
        assertEquals("T2", page.tracks[0].name)
    }

    @Test
    fun uploadRejectsEmptyName() = runBlocking {
        val authorId = registerAccount()
        val result = service.upload("", validDraft(), authorId, "Uploader", now = 1000L)
        assertTrue(result is TrackShareService.UploadResult.Rejected)
        assertEquals(
            TrackShareService.Rejection.INVALID_NAME,
            (result as TrackShareService.UploadResult.Rejected).reason,
        )
    }

    @Test
    fun uploadRejectsInvalidDraft() = runBlocking {
        val authorId = registerAccount()
        // An empty draft has no boundaries and no finish line, so validation fails.
        val result = service.upload("Bad", TrackDraft(), authorId, "Uploader", now = 1000L)
        assertTrue(result is TrackShareService.UploadResult.Rejected)
        assertEquals(
            TrackShareService.Rejection.INVALID_DRAFT,
            (result as TrackShareService.UploadResult.Rejected).reason,
        )
    }

    @Test
    fun uploadEnforcesPerAuthorQuota() = runBlocking {
        val authorId = registerAccount()
        // Fill the quota.
        repeat(TrackShareService.MAX_TRACKS_PER_AUTHOR) { i ->
            val result = service.upload("Track$i", validDraft(), authorId, "Uploader", now = 1000L + i)
            assertTrue(result is TrackShareService.UploadResult.Ok)
        }
        // The next upload should be rejected.
        val over = service.upload("OverQuota", validDraft(), authorId, "Uploader", now = 2000L)
        assertTrue(over is TrackShareService.UploadResult.Rejected)
        assertEquals(
            TrackShareService.Rejection.QUOTA_EXCEEDED,
            (over as TrackShareService.UploadResult.Rejected).reason,
        )
    }

    @Test
    fun reportRecordsReportAndReturnsOk() = runBlocking {
        val authorId = registerAccount()
        val reporterId = registerAccount(username = "reporter", displayName = "Reporter")
        val upload = service.upload("Reported", validDraft(), authorId, "Uploader", now = 1000L)
        val trackId = (upload as TrackShareService.UploadResult.Ok).summary.trackId

        val result = service.report(trackId, reporterId, "Offensive content", now = 2000L)
        assertTrue(result is TrackShareService.ReportResult.Ok)
        assertEquals(trackId, (result as TrackShareService.ReportResult.Ok).trackId)
    }

    @Test
    fun reportReturnsNotFoundForUnknownTrack() = runBlocking {
        val reporterId = registerAccount(username = "reporter", displayName = "Reporter")
        val result = service.report("no-such-track", reporterId, "reason", now = 1000L)
        assertEquals(TrackShareService.ReportResult.NotFound, result)
    }

    @Test
    fun uploadedTrackIsServerRebuiltNotClientSupplied() = runBlocking {
        // The security guarantee: the server rebuilds the track from the draft. The client
        // never sends a compiled Track in the UploadTrack message (only a draft), so there
        // is no client-supplied track to discard. This test documents that the upload
        // message carries only a draft, and the server returns a track it built itself.
        val authorId = registerAccount()
        val result = service.upload("Server Built", validDraft(), authorId, "Uploader", now = 1000L)
        assertTrue(result is TrackShareService.UploadResult.Ok)
        val ok = result as TrackShareService.UploadResult.Ok
        // The track name matches the requested name (the server applies the name).
        assertEquals("Server Built", ok.track.name)
        // The track has walls derived from the draft boundaries.
        assertFalse(ok.track.walls.isEmpty())
    }

    @Test
    fun uploadWithServerIdProducesAUuidv5ShapedId() = runBlocking {
        val idService = TrackShareService(db, serverId = "11111111-1111-1111-1111-111111111111", serverName = "Test")
        val authorId = registerAccount()
        val result = idService.upload("Namespaced", validDraft(), authorId, "Uploader", now = 1000L) as TrackShareService.UploadResult.Ok
        // UUIDv5 (and v3) have version 5 (or 3) in the most-significant nibble of the third
        // group, and variant 2 in the fourth. nameUUIDFromBytes produces version 3.
        val parts = result.summary.trackId.split("-")
        assertEquals("3", parts[2].first().toString())
        // Variant: the fourth group starts with 8, 9, a or b.
        assertTrue("variant nibble", parts[3].first() in setOf('8', '9', 'a', 'b'))
        // The summary carries the server id/name and the author username.
        assertEquals("Test", result.summary.serverName)
        assertEquals("11111111-1111-1111-1111-111111111111", result.summary.serverId)
        assertEquals("uploader", result.summary.authorUsername)
    }

    @Test
    fun twoUploadsBySameAuthorProduceDistinctIds() = runBlocking {
        val idService = TrackShareService(db, serverId = "11111111-1111-1111-1111-111111111111")
        val authorId = registerAccount()
        val a = idService.upload("A", validDraft(), authorId, "Uploader", now = 1000L) as TrackShareService.UploadResult.Ok
        val b = idService.upload("B", validDraft(), authorId, "Uploader", now = 2000L) as TrackShareService.UploadResult.Ok
        assertNotEquals(a.summary.trackId, b.summary.trackId)
    }

    @Test
    fun rateRecordsAndAggregatesRatings() = runBlocking {
        val authorId = registerAccount()
        val rater1 = registerAccount(username = "rater1", displayName = "R1")
        val rater2 = registerAccount(username = "rater2", displayName = "R2")
        val upload = service.upload("Rated", validDraft(), authorId, "Uploader", now = 1000L) as TrackShareService.UploadResult.Ok
        val trackId = upload.summary.trackId

        val r1 = service.rate(trackId, rater1, 4, now = 2000L) as TrackShareService.RateResult.Ok
        assertEquals(4, r1.yourRating)
        assertEquals(4.0, r1.averageRating, 0.001)
        assertEquals(1, r1.ratingCount)

        val r2 = service.rate(trackId, rater2, 2, now = 3000L) as TrackShareService.RateResult.Ok
        assertEquals(3.0, r2.averageRating, 0.001)
        assertEquals(2, r2.ratingCount)

        // The list summary reflects the aggregate.
        val page = service.list(0, 10)
        assertEquals(3.0, page.tracks[0].averageRating, 0.001)
        assertEquals(2, page.tracks[0].ratingCount)
    }

    @Test
    fun rateReplacingYourRatingUpdatesTheAggregateNotDuplicatesIt() = runBlocking {
        val authorId = registerAccount()
        val rater = registerAccount(username = "rater", displayName = "R")
        val trackId = (service.upload("Rated", validDraft(), authorId, "Uploader", now = 1000L) as TrackShareService.UploadResult.Ok).summary.trackId

        service.rate(trackId, rater, 5, now = 2000L)
        val second = service.rate(trackId, rater, 1, now = 3000L) as TrackShareService.RateResult.Ok
        // One rater, one rating - the count stays at 1 and the average is the new value.
        assertEquals(1, second.ratingCount)
        assertEquals(1.0, second.averageRating, 0.001)
    }

    @Test
    fun rateRejectsOutOfRangeRatings() = runBlocking {
        val authorId = registerAccount()
        val rater = registerAccount(username = "rater", displayName = "R")
        val trackId = (service.upload("Rated", validDraft(), authorId, "Uploader", now = 1000L) as TrackShareService.UploadResult.Ok).summary.trackId

        assertTrue(service.rate(trackId, rater, 0, now = 2000L) is TrackShareService.RateResult.InvalidRating)
        assertTrue(service.rate(trackId, rater, 6, now = 2000L) is TrackShareService.RateResult.InvalidRating)
    }

    @Test
    fun rateReturnsNotFoundForUnknownTrack() = runBlocking {
        val rater = registerAccount(username = "rater", displayName = "R")
        assertEquals(TrackShareService.RateResult.NotFound, service.rate("no-such-track", rater, 3, now = 1000L))
    }
}
