package info.piepgras.gridracer.server.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [AuthService] and [Pbkdf2PasswordHasher] against an in-memory SQLite database.
 *
 * Each test gets a fresh [Database] so there is no cross-test state; the database is closed in
 * [After] to release its single-thread dispatcher.
 */
class AuthServiceTest {

    private val db = Database(":memory:")
    private var now = START_TIME
    private val auth = AuthService(db, clock = { now })

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun registerCreatesAnAccountAndIssuesASession() = runBlocking {
        val success = auth.register("alice", "correct horse", null).expectOk()
        assertEquals("alice", success.account.username)
        assertEquals("alice", success.account.displayName)
        assertEquals(AccountDao.STARTING_RATING, success.account.rating)
        assertTrue(success.sessionToken.isNotBlank())
    }

    @Test
    fun registerAcceptsUnderscoresAndDigits() = runBlocking {
        val success = auth.register("player_2", "password", null).expectOk()
        assertEquals("player_2", success.account.username)
    }

    @Test
    fun registerRejectsAnInvalidUsername() = runBlocking {
        for (bad in listOf("", "ab", "1starts", "has space", "admin", "ai3")) {
            val reason = auth.register(bad, "password", null).expectRejected()
            assertTrue("'$bad' should be rejected", reason is AuthService.Rejection.Register)
        }
    }

    @Test
    fun usernameMustBeMoreThanThreeAndLessThanTwelveCharacters(): Unit = runBlocking {
        // 3 chars: too short (the requirement is "more than 3").
        auth.register("abc", "password", null).expectRejected()
        // 4 chars: the minimum accepted length.
        auth.register("abcd", "password", null).expectOk()
        // 11 chars: the maximum accepted length.
        auth.register("abcdefghijk", "password", null).expectOk()
        // 12 chars: too long (the requirement is "less than 12").
        auth.register("abcdefghijkl", "password", null).expectRejected()
    }

    @Test
    fun registerRejectsAShortPassword() = runBlocking {
        val reason = auth.register("bobby", "12345", null).expectRejected()
        assertEquals(AuthService.Rejection.Register.INVALID_PASSWORD, reason)
    }

    @Test
    fun registerRejectsATakenUsernameCaseInsensitively() = runBlocking {
        auth.register("alice", "password1", null).expectOk()
        val reason = auth.register("ALICE", "password2", null).expectRejected()
        assertEquals(AuthService.Rejection.Register.USERNAME_TAKEN, reason)
    }

    @Test
    fun loginSucceedsForCorrectCredentials() = runBlocking {
        auth.register("carol", "secret123", null).expectOk()
        val success = auth.login("carol", "secret123").expectOk()
        assertEquals("carol", success.account.username)
        assertTrue(success.sessionToken.isNotBlank())
    }

    @Test
    fun loginFailsForAnUnknownUser() = runBlocking {
        val reason = auth.login("nobody", "secret").expectRejected()
        assertEquals(AuthService.Rejection.Login.NO_SUCH_USER, reason)
    }

    @Test
    fun loginFailsForAWrongPassword() = runBlocking {
        auth.register("dave", "right-password", null).expectOk()
        val reason = auth.login("dave", "wrong-password").expectRejected()
        assertEquals(AuthService.Rejection.Login.WRONG_PASSWORD, reason)
    }

    @Test
    fun loginIsCaseInsensitiveOnUsername() = runBlocking {
        auth.register("Evee", "password", null).expectOk()
        val success = auth.login("evee", "password").expectOk()
        assertEquals("Evee", success.account.username)
    }

    @Test
    fun validateSessionResolvesAnIssuedToken() = runBlocking {
        val success = auth.register("frank", "password", null).expectOk()
        val account = auth.validateSession(success.sessionToken)
        assertNotNull(account)
        assertEquals("frank", account!!.username)
    }

    @Test
    fun validateSessionReturnsNullForAnUnknownToken() = runBlocking {
        assertNull(auth.validateSession("not-a-session"))
    }

    @Test
    fun logoutInvalidatesTheSession() = runBlocking {
        val success = auth.register("grace", "password", null).expectOk()
        auth.logout(success.sessionToken)
        assertNull(auth.validateSession(success.sessionToken))
    }

    @Test
    fun loginIssuesADifferentSessionEachTime() = runBlocking {
        auth.register("heidi", "password", null).expectOk()
        val first = auth.login("heidi", "password").expectOk().sessionToken
        val second = auth.login("heidi", "password").expectOk().sessionToken
        assertNotEquals(first, second)
    }

    @Test
    fun expiredSessionsAreNotValidAndAreReaped() = runBlocking {
        val success = auth.register("ivan", "password", null).expectOk()
        // Advance the clock past the session TTL.
        now = START_TIME + AuthService.SESSION_TTL_MILLIS + 1
        assertNull(auth.validateSession(success.sessionToken))
        assertEquals(1, auth.reapExpiredSessions())
    }

    @Test
    fun hasherRoundTripsAPassword() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1000)
        val hash = hasher.hash("hunter2".toCharArray())
        assertTrue(hasher.verify("hunter2".toCharArray(), hash))
        assertFalse(hasher.verify("wrong".toCharArray(), hash))
    }

    @Test
    fun hasherProducesDifferentHashesForTheSamePassword() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1000)
        val a = hasher.hash("same".toCharArray())
        val b = hasher.hash("same".toCharArray())
        assertNotEquals(a, b)
        assertTrue(hasher.verify("same".toCharArray(), a))
        assertTrue(hasher.verify("same".toCharArray(), b))
    }

    @Test
    fun hasherRejectsAMalformedStoredHash() {
        val hasher = Pbkdf2PasswordHasher(iterations = 1000)
        assertFalse(hasher.verify("x".toCharArray(), "not-a-real-hash"))
        assertFalse(hasher.verify("x".toCharArray(), "wrongalgo\$1\$a\$b"))
    }

    // ---- Account deletion (Google Play's "delete my account" requirement) -----------------

    @Test
    fun deleteAccountRemovesTheAccountAndItsSessions() = runBlocking {
        val success = auth.register("alice", "correct horse", null).expectOk()
        val result = auth.deleteAccount(success.account.id, "correct horse")

        assertEquals(success.account.publicId, (result as AuthService.DeleteResult.Ok).publicId)
        assertNull(auth.accountById(success.account.id))
        assertNull(auth.validateSession(success.sessionToken))
        // Logging back in is a miss, not a wrong password: the row is gone.
        assertEquals(
            AuthService.Rejection.Login.NO_SUCH_USER,
            auth.login("alice", "correct horse").expectRejected(),
        )
    }

    @Test
    fun deleteAccountRefusesAWrongPasswordAndChangesNothing() = runBlocking {
        val success = auth.register("alice", "correct horse", null).expectOk()

        assertEquals(
            AuthService.DeleteResult.WrongPassword,
            auth.deleteAccount(success.account.id, "not my password"),
        )
        assertNotNull(auth.accountById(success.account.id))
        assertNotNull(auth.validateSession(success.sessionToken))
    }

    @Test
    fun deleteAccountReportsAnAccountThatIsAlreadyGone() = runBlocking {
        assertEquals(AuthService.DeleteResult.NoSuchAccount, auth.deleteAccount(4321L, "password"))
    }

    @Test
    fun deleteAccountFreesTheUsername() = runBlocking {
        val first = auth.register("alice", "password1", null).expectOk()
        auth.deleteAccount(first.account.id, "password1")

        val second = auth.register("alice", "password2", "Someone Else").expectOk()
        assertNotEquals(first.account.publicId, second.account.publicId)
        assertEquals(AccountDao.STARTING_RATING, second.account.rating)
    }

    @Test
    fun deleteAccountTakesTheTracksItSharedWithIt() = runBlocking {
        val tracks = TrackShareService(db)
        val author = auth.register("author", "password1", null).expectOk().account
        tracks.upload("Their Track", simpleDraft(), author.id, "author", now = now)

        auth.deleteAccount(author.id, "password1")

        assertTrue(tracks.list(offset = 0, limit = 10).tracks.isEmpty())
    }

    /**
     * The trap this test exists for: `ON DELETE CASCADE` removes the deleted account's rating
     * rows, but SQLite has no idea the denormalized `rating_sum`/`rating_count` columns on
     * *someone else's* track were computed from them. Without the recompute in
     * [AuthService.deleteAccount] the track keeps advertising a rating nobody gave it.
     */
    @Test
    fun deleteAccountRepairsTheRatingAggregateOnTracksItHadRated() = runBlocking {
        val tracks = TrackShareService(db)
        val author = auth.register("author", "password1", null).expectOk().account
        val rater = auth.register("rater", "password2", null).expectOk().account
        val stays = auth.register("stays", "password3", null).expectOk().account
        val trackId = (
            tracks.upload("Shared", simpleDraft(), author.id, "author", now = now)
                as TrackShareService.UploadResult.Ok
            ).summary.trackId

        tracks.rate(trackId, rater.id, rating = 5, now = now)
        tracks.rate(trackId, stays.id, rating = 1, now = now)

        auth.deleteAccount(rater.id, "password2")

        val summary = tracks.list(offset = 0, limit = 10).tracks.single()
        assertEquals(1, summary.ratingCount)
        assertEquals(1.0, summary.averageRating, 0.0001)
    }

    private fun simpleDraft() = info.piepgras.gridracer.engine.TrackDraft(
        outerBoundary = listOf(
            info.piepgras.gridracer.engine.Point(0, 0),
            info.piepgras.gridracer.engine.Point(20, 0),
            info.piepgras.gridracer.engine.Point(20, 12),
            info.piepgras.gridracer.engine.Point(0, 12),
        ),
        innerBoundary = listOf(
            info.piepgras.gridracer.engine.Point(7, 4),
            info.piepgras.gridracer.engine.Point(13, 4),
            info.piepgras.gridracer.engine.Point(13, 7),
            info.piepgras.gridracer.engine.Point(7, 7),
        ),
        finishLineFrom = info.piepgras.gridracer.engine.Point(10, 7),
        finishLineTo = info.piepgras.gridracer.engine.Point(10, 12),
        finishDirection = info.piepgras.gridracer.engine.Point(-1, 0),
    )

    private fun AuthService.Result.expectOk(): AuthService.Success =
        (this as? AuthService.Result.Ok)?.success ?: error("expected Ok, got $this")

    private fun AuthService.Result.expectRejected(): AuthService.Rejection =
        (this as? AuthService.Result.Rejected)?.reason ?: error("expected Rejected, got $this")

    private companion object {
        const val START_TIME = 1_700_000_000_000L
    }
}
