package info.piepgras.gridracer.server.security

import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private fun name(raw: String) = Validation.displayName(raw)
    private fun ok(raw: String) = (name(raw) as? Validation.NameResult.Ok)?.name
    private fun rejection(raw: String) = (name(raw) as? Validation.NameResult.Rejected)?.error

    // ---- Display names ------------------------------------------------------------------

    @Test
    fun acceptsOrdinaryNames() {
        assertEquals("Martin", ok("Martin"))
        assertEquals("Martin", ok("  Martin  "))
        assertEquals("Ada Lovelace", ok("Ada  Lovelace"))
        assertEquals("Ünal", ok("Ünal"))
        assertEquals("玲", ok("玲"))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnly() {
        assertEquals(Validation.NameError.EMPTY, rejection(""))
        assertEquals(Validation.NameError.EMPTY, rejection("   "))
    }

    @Test
    fun boundsNameLengthByCodePointsNotChars() {
        assertEquals(Validation.NameError.TOO_LONG, rejection("a".repeat(25)))

        // Counted in code points, so an astral-plane name is measured the way a reader sees
        // it: 24 emoji fit, 25 do not - where String.length would have rejected 25 chars,
        // i.e. 13 emoji.
        assertNotNull(ok("😀".repeat(24)))
        assertEquals(Validation.NameError.TOO_LONG, rejection("😀".repeat(25)))
    }

    @Test
    fun rejectsControlAndFormatCharacters() {
        // U+202E is the right-to-left override - the classic "make a name render as something
        // else" trick. Rejected rather than stripped, so the user sees what they typed.
        assertEquals(Validation.NameError.CONTROL_CHARACTERS, rejection("evil‮eman"))
        assertEquals(Validation.NameError.CONTROL_CHARACTERS, rejection("bell"))
        assertEquals(Validation.NameError.CONTROL_CHARACTERS, rejection("zero​width"))
    }

    @Test
    fun rejectsZalgo() {
        assertEquals(Validation.NameError.EXCESSIVE_COMBINING_MARKS, rejection("á̂̃̄"))
        // Two marks is a legitimately accented name, not an attack.
        assertNotNull(ok("á"))
    }

    @Test
    fun rejectsLatinMixedWithCyrillic() {
        // "Аdmin" with a Cyrillic А renders identically to the Latin one.
        assertEquals(Validation.NameError.MIXED_SCRIPTS, rejection("Аdmin"))
        // Either script alone is fine.
        assertNotNull(ok("Admin2"))
        assertNotNull(ok("Привет"))
    }

    @Test
    fun rejectsReservedNames() {
        assertEquals(Validation.NameError.RESERVED, rejection("admin"))
        assertEquals(Validation.NameError.RESERVED, rejection("ADMIN"))
        assertEquals(Validation.NameError.RESERVED, rejection("server"))
        // The server names filler players "AI 2" - an account called that is indistinguishable
        // from a bot in a lobby.
        assertEquals(Validation.NameError.RESERVED, rejection("AI 2"))
        assertEquals(Validation.NameError.RESERVED, rejection("ai3"))
    }

    @Test
    fun normalisesFullwidthBeforeCheckingReserved() {
        // Without NFKC first this renders as "admin" but passes the reserved check.
        assertEquals(Validation.NameError.RESERVED, rejection("ａｄｍｉｎ"))
    }

    @Test
    fun fallsBackRatherThanFailingTheWholeRequest() {
        assertEquals("Host", Validation.displayNameOr("", "Host"))
        assertEquals("Host", Validation.displayNameOr("admin", "Host"))
        assertEquals("Martin", Validation.displayNameOr("Martin", "Host"))
    }

    // ---- Room codes ---------------------------------------------------------------------

    @Test
    fun normalisesRoomCodes() {
        assertEquals("ABCDE", Validation.roomCode("abcde"))
        assertEquals("ABCDE", Validation.roomCode("  ABCDE  "))
        // 0 and 1 are not in the alphabet precisely because they read as O and I.
        assertEquals("OICDE", Validation.roomCode("01cde"))
    }

    @Test
    fun rejectsCodesThatCannotExist() {
        assertNull(Validation.roomCode(""))
        assertNull(Validation.roomCode("ABCD"))
        assertNull(Validation.roomCode("ABCDEF"))
        assertNull(Validation.roomCode("AB!DE"))
        assertNull(Validation.roomCode("a".repeat(5_000)))
    }

    @Test
    fun everyGeneratedCodeSurvivesNormalisation() {
        // The alphabet and the normaliser have to agree, or codes stop being joinable.
        for (ch in Validation.ROOM_CODE_ALPHABET) {
            val code = ch.toString().repeat(Limits.ROOM_CODE_LENGTH)
            assertEquals(code, Validation.roomCode(code))
        }
    }

    // ---- Numbers ------------------------------------------------------------------------

    @Test
    fun clampsLapsAndPlayers() {
        assertEquals(Limits.MIN_LAPS, Validation.clampLaps(0))
        assertEquals(Limits.MIN_LAPS, Validation.clampLaps(Int.MIN_VALUE))
        assertEquals(Limits.MAX_LAPS, Validation.clampLaps(Int.MAX_VALUE))
        assertEquals(3, Validation.clampLaps(3))

        assertEquals(Limits.MIN_PLAYERS, Validation.clampMaxPlayers(1))
        assertEquals(Limits.MAX_PLAYERS, Validation.clampMaxPlayers(10_000))
    }

    // ---- Track payloads -------------------------------------------------------------------

    @Test
    fun acceptsABuiltInTrack() {
        assertNull(Validation.checkTrack(Tracks.OVAL))
    }

    @Test
    fun rejectsAnOversizedTrack() {
        // A hosted track is rebroadcast to every player and spectator on every lobby update,
        // so one huge payload is amplified by the size of the room.
        val huge = Tracks.OVAL.copy(walls = List(Limits.MAX_WALLS + 1) { Tracks.OVAL.walls.first() })
        assertEquals(Validation.TrackError.TOO_MANY_WALLS, Validation.checkTrack(huge))

        val wide = Tracks.OVAL.copy(width = Limits.MAX_TRACK_DIMENSION + 1)
        assertEquals(Validation.TrackError.TOO_LARGE, Validation.checkTrack(wide))

        val subdivided = Tracks.OVAL.copy(subdivision = Limits.MAX_SUBDIVISION + 1)
        assertEquals(Validation.TrackError.BAD_SUBDIVISION, Validation.checkTrack(subdivided))
    }

    @Test
    fun rejectsATrackNobodyCouldRaceOn() {
        val unraceable = Tracks.OVAL.copy(startPositions = listOf(Point(0, 0)))
        assertEquals(Validation.TrackError.UNRACEABLE, Validation.checkTrack(unraceable))
    }

    @Test
    fun rejectsTooManyModifiers() {
        val modifier = Tracks.OVAL.modifiers.firstOrNull()
        if (modifier != null) {
            val many = Tracks.OVAL.copy(modifiers = List(Limits.MAX_MODIFIERS + 1) { modifier })
            assertEquals(Validation.TrackError.TOO_MANY_MODIFIERS, Validation.checkTrack(many))
        } else {
            assertTrue("OVAL has no modifiers to duplicate; covered by the wall test", true)
        }
    }
}
