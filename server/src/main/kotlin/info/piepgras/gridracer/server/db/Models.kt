package info.piepgras.gridracer.server.db

/**
 * An account row as the server stores it. [passwordHash] never leaves the DB layer.
 *
 * [publicId] is the opaque, wire-safe handle (a UUID) that the leaderboard and any future
 * profile feature point at - it is generated independently of every secret and is never
 * accepted as authentication, mirroring the rule the old `RatingsManager.publicId` held.
 */
data class Account(
    /** The row id. Internal only: never sent to a client and never used as a handle. */
    val id: Long,
    /** Opaque UUID handle for the leaderboard; see the class description. */
    val publicId: String,
    /** Login name, unique; see [info.piepgras.gridracer.multiplayer.AccountRules] for its rules. */
    val username: String,
    /** The PBKDF2 hash string the password is verified against; never leaves the DB layer. */
    val passwordHash: String,
    /** Name shown in lobbies and on the leaderboard. */
    val displayName: String,
    /** Current Elo rating. */
    val rating: Int,
    /** Rated races played. */
    val matches: Int,
    /** Rated races won. */
    val wins: Int,
)

/** A session row. The [token] is the credential presented to `Identify`. */
data class StoredSession(
    /** The session token itself - a bearer credential. */
    val token: String,
    /** The [Account.id] the session belongs to. */
    val accountId: Long,
    /** When the session stops being accepted, in epoch millis. */
    val expiresAt: Long,
)
