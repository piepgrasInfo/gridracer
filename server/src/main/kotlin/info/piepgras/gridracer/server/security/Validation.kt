package info.piepgras.gridracer.server.security

import info.piepgras.gridracer.engine.Track
import java.text.Normalizer

/**
 * Pure input validation. No I/O, no coroutines, no server state - so every rule here is
 * cheap to test exhaustively, which is the point.
 */
object Validation {

    /** Why a name was refused. The caller decides how to phrase it. */
    enum class NameError {
        EMPTY,
        TOO_LONG,
        CONTROL_CHARACTERS,
        EXCESSIVE_COMBINING_MARKS,
        MIXED_SCRIPTS,
        RESERVED,
    }

    /** The outcome of checking a display name: the cleaned name, or why it was refused. */
    sealed interface NameResult {
        /**
         * The name is acceptable.
         *
         * @property name The name as it should be stored and shown: NFKC-normalised, trimmed, inner
         *     whitespace collapsed.
         */
        data class Ok(val name: String) : NameResult
        /**
         * The name was refused.
         *
         * @property error Why.
         */
        data class Rejected(val error: NameError) : NameResult
    }

    /**
     * Names the server uses itself, which an impersonator must not be able to take. The AI
     * pattern matters because the server names filler players "AI 2" - an account called that
     * is indistinguishable from a bot in a lobby.
     */
    private val RESERVED = setOf(
        "admin", "administrator", "moderator", "mod", "system", "server", "gridracer",
        "ai", "bot", "player", "host", "guest", "null", "undefined", "none",
    )
    private val RESERVED_PATTERN = Regex("""^ai\s*\d+$""", RegexOption.IGNORE_CASE)

    /**
     * Cleans and checks a display name - room host/player names, and later account display
     * names and shared-track names.
     *
     * Rejects rather than silently strips: quietly removing characters shows the user a
     * different name than they typed, which is its own kind of surprise.
     */
    fun displayName(raw: String): NameResult {
        // NFKC first: it collapses fullwidth and ligature forms, so "ａdmin" cannot slip past
        // the reserved list as something that renders identically to "admin".
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE_RUN, " ")

        if (normalized.isEmpty()) return NameResult.Rejected(NameError.EMPTY)

        if (normalized.codePoints().anyMatch(::isProblematicFormatting)) {
            return NameResult.Rejected(NameError.CONTROL_CHARACTERS)
        }

        val codePoints = normalized.codePointCount(0, normalized.length)
        if (codePoints > Limits.DISPLAY_NAME_MAX_CODE_POINTS ||
            normalized.length > Limits.DISPLAY_NAME_MAX_UTF16 ||
            normalized.toByteArray(Charsets.UTF_8).size > Limits.DISPLAY_NAME_MAX_UTF8_BYTES
        ) {
            return NameResult.Rejected(NameError.TOO_LONG)
        }

        if (hasCombiningMarkRun(normalized)) {
            return NameResult.Rejected(NameError.EXCESSIVE_COMBINING_MARKS)
        }

        if (mixesConfusableScripts(normalized)) {
            return NameResult.Rejected(NameError.MIXED_SCRIPTS)
        }

        if (isReserved(normalized)) {
            return NameResult.Rejected(NameError.RESERVED)
        }

        return NameResult.Ok(normalized)
    }

    /** A name that is refused falls back to this rather than failing the whole request. */
    fun displayNameOr(raw: String, fallback: String): String =
        (displayName(raw) as? NameResult.Ok)?.name ?: fallback

    /**
     * Whether [name] is one the server uses itself - "admin", "system", "AI 2" and the like -
     * compared case-insensitively.
     *
     * @return `true` if an account or a player may not use the name.
     */
    fun isReserved(name: String): Boolean {
        val folded = name.lowercase()
        return folded in RESERVED || RESERVED_PATTERN.matches(folded)
    }

    /**
     * Control and format characters. `Cf` is the important one: it covers the
     * right-to-left override that lets a name render as something else entirely.
     *
     * Takes a **code point**, not a Char. Walking a String by Char sees a surrogate pair as
     * two lone `SURROGATE` chars, which would reject every emoji and every other astral
     * character as a control character.
     */
    private fun isProblematicFormatting(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }

    private fun hasCombiningMarkRun(text: String): Boolean {
        var run = 0
        for (cp in text.codePoints()) {
            val combining = when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> true
                else -> false
            }
            run = if (combining) run + 1 else 0
            if (run > Limits.MAX_CONSECUTIVE_COMBINING_MARKS) return true
        }
        return false
    }

    /**
     * A deliberately cheap homoglyph defence: reject a name mixing Latin with Cyrillic or
     * Greek, which is how "Аdmin" (Cyrillic А) is built.
     *
     * This is a heuristic, not a full UTS #39 confusable skeleton - that needs ICU and is
     * disproportionate here. The real defence is that leaderboard rows carry a stable
     * publicId, so a copied name still isn't a copied identity.
     */
    private fun mixesConfusableScripts(text: String): Boolean {
        var latin = false
        var cyrillicOrGreek = false
        for (cp in text.codePoints()) {
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> latin = true
                Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK -> cyrillicOrGreek = true
                else -> Unit
            }
        }
        return latin && cyrillicOrGreek
    }

    private val WHITESPACE_RUN = Regex("""\s+""")

    // ---- Usernames ---------------------------------------------------------------------

    /** Why a username was refused; see [username] for the rules each value stands for. */
    enum class UsernameError { EMPTY, TOO_SHORT, TOO_LONG, INVALID_CHARACTERS, MUST_START_WITH_LETTER, RESERVED }

    /**
     * Account usernames are stricter than display names: ASCII letters, digits and underscores
     * only, must start with a letter, [Limits.USERNAME_MIN_LENGTH]..[Limits.USERNAME_MAX_LENGTH]
     * characters. They are typed and read aloud more often than display names, so confusable
     * scripts and combining marks are ruled out by construction rather than detected. Reserved
     * names (admin, system, the AI pattern, …) are refused for the same reason as display names.
     *
     * The length and character rules mirror `AccountRules` in the shared module, which the app
     * pre-checks against; the reserved-name check stays here, because a client cannot know the
     * reserved list and a guess at it would be worse than no guess.
     */
    fun username(raw: String): UsernameError? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return UsernameError.EMPTY
        if (normalized.length < Limits.USERNAME_MIN_LENGTH) return UsernameError.TOO_SHORT
        if (normalized.length > Limits.USERNAME_MAX_LENGTH) return UsernameError.TOO_LONG
        if (!normalized.first().isLetter()) return UsernameError.MUST_START_WITH_LETTER
        if (!normalized.all { it.isLetterOrDigit() || it == '_' }) return UsernameError.INVALID_CHARACTERS
        if (isReserved(normalized)) return UsernameError.RESERVED
        return null
    }

    // ---- Room codes ---------------------------------------------------------------------

    /** The alphabet room codes are generated from: no 0/1, which read as O/I. */
    val ROOM_CODE_ALPHABET: List<Char> = ('A'..'Z') + ('2'..'9')

    /**
     * Normalises a typed room code, or returns null if it cannot be one.
     *
     * Rejecting here is free and keeps garbage out of the room map; the confusable folding
     * means a code read aloud and typed back with an O for a 0 still works. Codes were
     * previously looked up verbatim, so a lowercased code simply failed.
     */
    fun roomCode(raw: String): String? {
        val folded = raw.trim().uppercase().map { ch ->
            when (ch) {
                '0' -> 'O'
                '1' -> 'I'
                else -> ch
            }
        }.joinToString("")

        if (folded.length != Limits.ROOM_CODE_LENGTH) return null
        if (folded.any { it !in ROOM_CODE_ALPHABET }) return null
        return folded
    }

    // ---- Numbers ------------------------------------------------------------------------

    /** Forces a requested lap count into [Limits.MIN_LAPS]..[Limits.MAX_LAPS]. */
    fun clampLaps(laps: Int): Int = laps.coerceIn(Limits.MIN_LAPS, Limits.MAX_LAPS)

    /** Forces a requested seat count into [Limits.MIN_PLAYERS]..[Limits.MAX_PLAYERS]. */
    fun clampMaxPlayers(maxPlayers: Int): Int =
        maxPlayers.coerceIn(Limits.MIN_PLAYERS, Limits.MAX_PLAYERS)

    /** Forces a requested turn time limit into the range [Limits] allows. */
    fun clampTurnTimeoutSeconds(seconds: Int): Int =
        seconds.coerceIn(Limits.MIN_TURN_TIMEOUT_SECONDS, Limits.MAX_TURN_TIMEOUT_SECONDS)

    // ---- Track payloads -------------------------------------------------------------------

    /** Why [checkTrack] refused a track a client asked the server to host. */
    enum class TrackError { TOO_MANY_WALLS, TOO_MANY_MODIFIERS, TOO_LARGE, BAD_SUBDIVISION, UNRACEABLE }

    /**
     * Bounds a track a client wants the server to hold and rebroadcast.
     *
     * This is a size and sanity check, not a validity check - a hosted track is the host's
     * own problem if it is unplayable. It exists because the server re-sends the whole track
     * to every player and spectator on every lobby update, so one oversized payload is
     * amplified by the size of the room.
     */
    fun checkTrack(track: Track): TrackError? = when {
        track.walls.size > Limits.MAX_WALLS -> TrackError.TOO_MANY_WALLS
        track.modifiers.size > Limits.MAX_MODIFIERS -> TrackError.TOO_MANY_MODIFIERS
        track.width !in 1..Limits.MAX_TRACK_DIMENSION -> TrackError.TOO_LARGE
        track.height !in 1..Limits.MAX_TRACK_DIMENSION -> TrackError.TOO_LARGE
        track.subdivision !in 1..Limits.MAX_SUBDIVISION -> TrackError.BAD_SUBDIVISION
        track.startPositions.size < Limits.MIN_PLAYERS -> TrackError.UNRACEABLE
        else -> null
    }
}
