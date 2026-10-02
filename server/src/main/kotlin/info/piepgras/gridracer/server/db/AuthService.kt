package info.piepgras.gridracer.server.db

import info.piepgras.gridracer.server.security.Limits
import info.piepgras.gridracer.server.security.Validation
import java.util.UUID

/**
 * Account registration, login and session management.
 *
 * All DB work runs on the [Database] thread through [read]/[write]; this service holds no
 * mutable state of its own and is safe to call from any coroutine. Sessions are persisted, so
 * a server restart does not log everyone out - the trade-off is that a stolen session token
 * is valid until it expires, which is why the token is treated as a bearer credential
 * everywhere it is accepted.
 */
class AuthService(
    private val db: Database,
    private val hasher: Pbkdf2PasswordHasher = Pbkdf2PasswordHasher(),
    private val accountDao: AccountDao = AccountDao(),
    /** Only used by [deleteAccount], to repair the rating aggregates the cascade leaves stale. */
    private val trackShareDao: TrackShareDao = TrackShareDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Why a register or login attempt was refused. */
    sealed interface Rejection {
        /**
         * Why a registration was refused: the username, password or display name broke a rule, or
         * the username is taken.
         */
        enum class Register : Rejection { INVALID_USERNAME, INVALID_PASSWORD, USERNAME_TAKEN, INVALID_DISPLAY_NAME }
        /** Why a login was refused: no account has that username, or the password did not match. */
        enum class Login : Rejection { NO_SUCH_USER, WRONG_PASSWORD }
    }

    /**
     * A successful authentication carries the account and the session token to present next time.
     *
     * @property account The account now logged in.
     * @property sessionToken The new session token; see [Account] and `StoredSession`.
     */
    data class Success(val account: Account, val sessionToken: String)

    /** The outcome of [register] or [login]. */
    sealed interface Result {
        /**
         * The request succeeded.
         *
         * @property success The account and its new session.
         */
        data class Ok(val success: Success) : Result
        /**
         * The request was refused.
         *
         * @property reason Why.
         */
        data class Rejected(val reason: Rejection) : Result
    }

    /** The outcome of a [deleteAccount] attempt. */
    sealed interface DeleteResult {
        /**
         * The account is gone. [publicId] is the opaque handle it used to have, which the
         * caller needs to scrub the account out of stored race logs - after this transaction
         * there is no row left to look it up from.
         *
         * @property publicId The deleted account's former public id.
         */
        data class Ok(val publicId: String) : DeleteResult
        /** The re-entered password did not match; nothing was deleted. */
        data object WrongPassword : DeleteResult
        /** The account no longer exists - deleted elsewhere in the meantime. */
        data object NoSuchAccount : DeleteResult
    }

    /** Creates a new account and an initial session. */
    suspend fun register(username: String, password: String, displayName: String?): Result {
        val usernameError = Validation.username(username)
        if (usernameError != null) return Result.Rejected(Rejection.Register.INVALID_USERNAME)
        if (!isValidPassword(password)) return Result.Rejected(Rejection.Register.INVALID_PASSWORD)
        val display = displayName?.takeIf { it.isNotBlank() }?.let { Validation.displayName(it) }
        if (display != null && display is Validation.NameResult.Rejected) {
            return Result.Rejected(Rejection.Register.INVALID_DISPLAY_NAME)
        }
        val resolvedDisplay = (display as? Validation.NameResult.Ok)?.name ?: username
        val now = clock()
        val token = newSessionToken()
        val outcome = db.write { conn ->
            val account = accountDao.insert(
                conn,
                publicId = AccountDao.newPublicId(),
                username = username,
                passwordHash = hasher.hash(password.toCharArray()),
                displayName = resolvedDisplay,
                now = now,
            ) ?: return@write null
            accountDao.insertSession(conn, token, account.id, now, now + SESSION_TTL_MILLIS)
            account
        }
        return outcome?.let { Result.Ok(Success(it, token)) }
            ?: Result.Rejected(Rejection.Register.USERNAME_TAKEN)
    }

    /** Validates credentials and issues a new session, invalidating nothing. */
    suspend fun login(username: String, password: String): Result {
        val now = clock()
        val token = newSessionToken()
        val outcome: Any = db.write { conn ->
            val account = accountDao.findByUsername(conn, username) ?: return@write LoginMiss.NO_SUCH_USER
            if (!hasher.verify(password.toCharArray(), account.passwordHash)) {
                return@write LoginMiss.WRONG_PASSWORD
            }
            accountDao.insertSession(conn, token, account.id, now, now + SESSION_TTL_MILLIS)
            account
        }
        return when (outcome) {
            LoginMiss.NO_SUCH_USER -> Result.Rejected(Rejection.Login.NO_SUCH_USER)
            LoginMiss.WRONG_PASSWORD -> Result.Rejected(Rejection.Login.WRONG_PASSWORD)
            else -> Result.Ok(Success(outcome as Account, token))
        }
    }

    /** Resolves a session token to its account, or null if the token is unknown or expired. */
    suspend fun validateSession(token: String): Account? = db.read { conn ->
        val session = accountDao.findSession(conn, token, clock()) ?: return@read null
        accountDao.findById(conn, session.accountId)
    }

    /** Invalidates a session token. No-op if the token was not valid. */
    suspend fun logout(token: String) {
        db.write { conn -> accountDao.deleteSession(conn, token) }
    }

    /**
     * Permanently deletes [accountId] after re-checking [password]. Google Play requires this
     * of any app that lets a user create an account; see `legal/delete-account.md`.
     *
     * One transaction, so a half-deleted account cannot exist: the row goes, and with it - by
     * `ON DELETE CASCADE` - its sessions, the tracks it shared, and the ratings and reports it
     * filed. The one thing the cascade cannot keep straight is the denormalized rating
     * aggregate on *other people's* tracks this account had rated, which is why the rated ids
     * are read first and recomputed after.
     *
     * The password check runs on the DB thread inside the write, exactly as [login]'s does:
     * PBKDF2 is deliberately slow, and this is a rare call on a path that is already
     * rate-limited per socket and per address.
     */
    suspend fun deleteAccount(accountId: Long, password: String): DeleteResult = db.write { conn ->
        val account = accountDao.findById(conn, accountId) ?: return@write DeleteResult.NoSuchAccount
        if (!hasher.verify(password.toCharArray(), account.passwordHash)) {
            return@write DeleteResult.WrongPassword
        }
        val ratedTracks = trackShareDao.ratedTrackIds(conn, accountId)
        if (!accountDao.deleteAccount(conn, accountId)) return@write DeleteResult.NoSuchAccount
        // After the cascade: tracks this account rated have lost a rating row, and tracks it
        // authored are gone entirely (the update finds no row, which is the intended no-op).
        for (trackId in ratedTracks) trackShareDao.recomputeRatingAggregate(conn, trackId)
        DeleteResult.Ok(account.publicId)
    }

    /** Looks up an account by its internal id (for resolving track authorship). */
    suspend fun accountById(id: Long): Account? = db.read { conn ->
        accountDao.findById(conn, id)
    }

    /** Removes expired sessions. Called from the periodic cleanup loop. */
    suspend fun reapExpiredSessions(): Int = db.write { conn ->
        accountDao.deleteExpiredSessions(conn, clock())
    }

    private fun isValidPassword(password: String): Boolean =
        password.length in Limits.PASSWORD_MIN_LENGTH..Limits.PASSWORD_MAX_LENGTH

    private fun newSessionToken(): String = UUID.randomUUID().toString()

    /** A sentinel for the login miss reason, distinguishable from a returned [Account]. */
    private sealed interface LoginMiss {
        data object NO_SUCH_USER : LoginMiss
        data object WRONG_PASSWORD : LoginMiss
    }

    companion object {
        /** 90 days. Long-lived because re-entering a password on every server restart is hostile to a game. */
        const val SESSION_TTL_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}
