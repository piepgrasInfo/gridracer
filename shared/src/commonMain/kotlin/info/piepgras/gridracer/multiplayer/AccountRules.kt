package info.piepgras.gridracer.multiplayer

/**
 * The account rules both peers have to agree on, defined once.
 *
 * These lived only on the server (`security.Limits` / `security.Validation`), which the app
 * cannot see - so the app hard-coded its own copy, and the copies drifted: the sign-up hint
 * said "3-24 characters" while the server enforced 4-11, and the submit button was enabled for
 * a username the server was certain to refuse. The user found out by having their registration
 * rejected with a message that did not explain why.
 *
 * The server still validates - a client is never trusted - but now it validates against the
 * same constants the client shows and checks against.
 *
 * Only the rules a *client* can usefully pre-check are here. Everything the server decides on
 * its own (reserved names, homoglyph mixing, quota) stays server-side: those need the server's
 * own state or tables, and a client guess at them would be worse than no guess.
 */
object AccountRules {

    /**
     * Account usernames: ASCII letters, digits and underscores, starting with a letter.
     *
     * Stricter than display names on purpose - usernames are typed and read aloud, so
     * confusable scripts and combining marks are ruled out by construction rather than
     * detected afterwards.
     */
    const val USERNAME_MIN_LENGTH = 4
    /** Longest accepted username, in characters; see [USERNAME_MIN_LENGTH]. */
    const val USERNAME_MAX_LENGTH = 11

    /** The upper bound also guards the server against pathological inputs to PBKDF2. */
    const val PASSWORD_MIN_LENGTH = 6
    /**
     * Longest accepted password, in characters - a bound on the work one PBKDF2 hash can cost the
     * server.
     */
    const val PASSWORD_MAX_LENGTH = 256

    /** Display names shown in lobbies and on the leaderboard. */
    const val DISPLAY_NAME_MAX_CODE_POINTS = 24

    /**
     * The character and length rules for a username. Returns false for anything the server is
     * certain to refuse; true does **not** promise acceptance - the server also checks the
     * reserved list and uniqueness, which a client cannot know about.
     */
    fun isUsernameValid(raw: String): Boolean {
        val name = raw.trim()
        if (name.length !in USERNAME_MIN_LENGTH..USERNAME_MAX_LENGTH) return false
        if (!name.first().isAsciiLetter()) return false
        return name.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }
    }

    /**
     * ASCII-only on purpose: [Char.isLetter] is true for every alphabetic character in
     * Unicode, which would let a username through here that the server's ASCII rule refuses.
     */
    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
