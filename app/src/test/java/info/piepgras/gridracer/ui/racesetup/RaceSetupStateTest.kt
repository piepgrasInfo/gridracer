package info.piepgras.gridracer.ui.racesetup

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.MAX_TOURNAMENT_TRACKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified race setup screen's whole validation surface. [plan] is deliberately free of
 * Compose and Android types so the rules that used to be spread across four setup screens -
 * each with its own idea of the seat cap - can be pinned down here in plain JUnit.
 */
class RaceSetupStateTest {

    /** A track that seats exactly [seats] cars. Geometry is irrelevant to [plan]. */
    private fun trackSeating(seats: Int, name: String = "T$seats"): Track =
        Tracks.OVAL.copy(
            name = name,
            startPositions = List(seats) { Point(it, 0) },
        )

    private val fourSeater = trackSeating(4, "Four")
    private val catalogue = listOf(fourSeater)

    private fun field(vararg slots: PlayerSlot) = slots.toList()

    private val H = PlayerSlot.HUMAN
    private val A = PlayerSlot.AI
    private val X = PlayerSlot.NONE

    // ---------------------------------------------------------------------------------------
    // Seat cap
    // ---------------------------------------------------------------------------------------

    @Test
    fun seatCap_followsTheTrackWhenItSeatsFewerThanFour() {
        val plan = RaceSetupState(slots = field(H, H, H, H)).plan(listOf(trackSeating(3)))

        assertEquals(3, plan.seatCap)
        assertEquals(3, plan.effectivePlayerCount)
        assertNull(plan.blocker)
    }

    @Test
    fun seatCap_isCappedAtMaxPlayersForARoomierTrack() {
        // Regression test: the online host form capped only by startPositions.size, so a
        // six-start-position track could host six cars - two of them sharing a colour with
        // another car, since PlayerColors only has four entries.
        val plan = RaceSetupState(mode = SetupMode.HOST, maxPlayers = 6).plan(listOf(trackSeating(6)))

        assertEquals(MAX_PLAYERS, plan.seatCap)
        assertEquals(MAX_PLAYERS, plan.effectivePlayerCount)
    }

    @Test
    fun seatCap_followsTheNarrowestTrackInATournamentItinerary() {
        val plan = RaceSetupState(
            format = SetupFormat.TOURNAMENT,
            itinerary = listOf(trackSeating(4, "wide"), trackSeating(2, "narrow"), trackSeating(3, "mid")),
            slots = field(H, H, H, H),
        ).plan(catalogue)

        assertEquals(2, plan.seatCap)
        assertEquals(2, plan.effectivePlayerCount)
        assertNull(plan.blocker)
    }

    @Test
    fun seatCap_capsOnlyTheUpperEndSoAnUnraceableTrackStaysUnraceable() {
        // A one-seat track must surface as unraceable, never be floated up to MIN_PLAYERS.
        val plan = RaceSetupState().plan(listOf(trackSeating(1)))

        assertEquals(1, plan.seatCap)
        assertEquals(SetupBlocker.TRACK_UNRACEABLE, plan.blocker)
        assertFalse(plan.canSubmit)
    }

    @Test
    fun seatCap_isZeroForATrackWithNoStartPositionsAtAll() {
        val plan = RaceSetupState().plan(listOf(trackSeating(0)))

        assertEquals(0, plan.seatCap)
        assertEquals(SetupBlocker.TRACK_UNRACEABLE, plan.blocker)
    }

    // ---------------------------------------------------------------------------------------
    // Seats: the field and its size are the same control
    // ---------------------------------------------------------------------------------------

    @Test
    fun aFreshSetupSeatsTwoHumans() {
        val plan = RaceSetupState().plan(catalogue)

        assertEquals(2, plan.effectivePlayerCount)
        assertEquals(field(H, H, X, X), plan.slots)
    }

    @Test
    fun fillingASeatGrowsTheField() {
        val plan = RaceSetupState(slots = field(H, H, A, X)).plan(catalogue)

        assertEquals(3, plan.effectivePlayerCount)
        assertEquals(listOf(false, false, true), plan.configs.map { it.isAi })
    }

    @Test
    fun emptyingSeatsBelowTwoIsRefused() {
        val plan = RaceSetupState(slots = field(H, X, X, X)).plan(catalogue)

        assertEquals(SetupBlocker.NOT_ENOUGH_PLAYERS, plan.blocker)
        assertFalse(plan.canSubmit)
    }

    @Test
    fun anEmptySeatIsSkippedRatherThanRacedAsABlank() {
        val plan = RaceSetupState(
            names = listOf("Ada", "Bo", "Cy", "Di"),
            slots = field(H, X, H, H),
        ).plan(catalogue)

        assertEquals(listOf("Ada", "Cy", "Di"), plan.configs.map { it.name })
    }

    @Test
    fun seatsPastTheTracksCapacityAreEmptiedFromTheBack() {
        val plan = RaceSetupState(slots = field(H, H, H, H)).plan(listOf(trackSeating(2)))

        assertEquals(field(H, H, X, X), plan.slots)
        assertEquals(2, plan.configs.size)
    }

    @Test
    fun narrowingTheTrackDoesNotDestroyTheFieldTheUserSetUp() {
        // The capping is derived, so widening the track again brings the seats back.
        val state = RaceSetupState(slots = field(H, A, H, A))

        assertEquals(2, state.plan(listOf(trackSeating(2))).effectivePlayerCount)
        assertEquals(4, state.plan(catalogue).effectivePlayerCount)
    }

    @Test
    fun raceIndexFollowsPositionInTheFieldNotTheRow() {
        // Emptying seat 2 moves the seats behind it up a place - and their colours with them.
        val plan = RaceSetupState(slots = field(H, X, H, H)).plan(catalogue)

        assertEquals(0, plan.raceIndexOf(0))
        assertEquals(1, plan.raceIndexOf(2))
        assertEquals(2, plan.raceIndexOf(3))
    }

    @Test
    fun hostingSizesTheRoomRatherThanSeatingTheField() {
        val plan = RaceSetupState(mode = SetupMode.HOST, maxPlayers = 3, slots = field(H, H, X, X))
            .plan(catalogue)

        assertEquals(3, plan.effectivePlayerCount)
        assertTrue(plan.configs.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------

    @Test
    fun duplicateHumanNamesBlockALocalRace() {
        val plan = RaceSetupState(names = listOf("Ada", "Ada", "P3", "P4")).plan(catalogue)

        assertEquals(SetupBlocker.DUPLICATE_HUMAN_NAMES, plan.blocker)
    }

    @Test
    fun duplicateAiNamesAreAllowed() {
        val plan = RaceSetupState(
            names = listOf("Ada", "Ada", "P3", "P4"),
            slots = field(H, A, X, X),
        ).plan(catalogue)

        assertNull(plan.blocker)
    }

    @Test
    fun anEmptySeatsNameCannotCollide() {
        val plan = RaceSetupState(
            names = listOf("Ada", "Ada", "Bo", "Cy"),
            slots = field(H, X, H, X),
        ).plan(catalogue)

        assertNull(plan.blocker)
    }

    @Test
    fun aBlankNameFallsBackToItsSlotDefaultAndCanThenCollide() {
        // P2 is left blank, so it becomes "Player 2" - which P1 has already typed.
        val plan = RaceSetupState(names = listOf("Player 2", "", "P3", "P4")).plan(catalogue)

        assertEquals("Player 2", plan.configs[1].name)
        assertEquals(SetupBlocker.DUPLICATE_HUMAN_NAMES, plan.blocker)
    }

    @Test
    fun duplicateNamesAreNotEnforcedOnline() {
        // Online you supply one name and the server assigns the rest, so a collision there
        // isn't yours to fix - a blocker would be unclearable.
        val plan = RaceSetupState(
            mode = SetupMode.HOST,
            names = listOf("Ada", "Ada", "P3", "P4"),
        ).plan(catalogue)

        assertNull(plan.blocker)
        assertTrue(plan.configs.isEmpty())
    }

    @Test
    fun sanitizeNameCapsLength() {
        assertEquals(MAX_NAME_LENGTH, sanitizeName("a".repeat(50)).length)
        assertEquals("Ada", sanitizeName("Ada"))
    }

    // ---------------------------------------------------------------------------------------
    // Tournament itinerary
    // ---------------------------------------------------------------------------------------

    @Test
    fun aTournamentNeedsAtLeastTwoTracks() {
        val plan = RaceSetupState(
            format = SetupFormat.TOURNAMENT,
            itinerary = listOf(fourSeater),
        ).plan(catalogue)

        assertEquals(SetupBlocker.ITINERARY_TOO_SHORT, plan.blocker)
    }

    @Test
    fun aTournamentRefusesMoreTracksThanTheServerAccepts() {
        // The bound is shared with the server (TournamentRules), so an itinerary the setup
        // screen accepts is one the server will host.
        val plan = RaceSetupState(
            format = SetupFormat.TOURNAMENT,
            itinerary = List(MAX_TOURNAMENT_TRACKS + 1) { fourSeater },
        ).plan(catalogue)

        assertEquals(SetupBlocker.ITINERARY_TOO_LONG, plan.blocker)
    }

    @Test
    fun theItineraryIsOnlyCarriedInTournamentFormat() {
        val state = RaceSetupState(itinerary = listOf(fourSeater, fourSeater))

        assertTrue(state.plan(catalogue).itinerary.isEmpty())
        assertEquals(2, state.copy(format = SetupFormat.TOURNAMENT).plan(catalogue).itinerary.size)
    }

    // ---------------------------------------------------------------------------------------
    // Room-code modes
    // ---------------------------------------------------------------------------------------

    @Test
    fun joiningAndSpectatingNeedARoomCode() {
        assertEquals(SetupBlocker.NO_ROOM_CODE, RaceSetupState(mode = SetupMode.JOIN).plan(catalogue).blocker)
        assertEquals(SetupBlocker.NO_ROOM_CODE, RaceSetupState(mode = SetupMode.SPECTATE).plan(catalogue).blocker)
        assertNull(RaceSetupState(mode = SetupMode.JOIN, roomCode = "ABCD").plan(catalogue).blocker)
    }

    @Test
    fun joiningIgnoresAnUnraceableSelectedTrack() {
        // The room decides the track, so a stale local selection must not block the join.
        val plan = RaceSetupState(mode = SetupMode.JOIN, roomCode = "ABCD").plan(listOf(trackSeating(1)))

        assertNull(plan.blocker)
        assertNull(plan.track)
    }

    // ---------------------------------------------------------------------------------------
    // Track catalogue edge cases
    // ---------------------------------------------------------------------------------------

    @Test
    fun anEmptyCatalogueBlocksEverythingThatDescribesARace() {
        assertEquals(SetupBlocker.NO_TRACKS, RaceSetupState().plan(emptyList()).blocker)
        assertEquals(
            SetupBlocker.NO_TRACKS,
            RaceSetupState(mode = SetupMode.HOST).plan(emptyList()).blocker,
        )
    }

    @Test
    fun aStaleTrackIndexIsClampedRatherThanThrowing() {
        // A custom track deleted since the last visit shortens the catalogue under the index.
        val plan = RaceSetupState(selectedTrackIndex = 12).plan(catalogue)

        assertEquals(fourSeater.name, plan.track?.name)
    }

    @Test
    fun reversingRebuildsTheStartGridBeforeTheSeatCapIsReadOffIt() {
        val plan = RaceSetupState(reversedTracks = setOf(0)).plan(listOf(Tracks.OVAL))
        val forward = RaceSetupState().plan(listOf(Tracks.OVAL))

        assertTrue(plan.track!!.finishDirection != forward.track!!.finishDirection)
        assertEquals(plan.track.startPositions.size.coerceAtMost(MAX_PLAYERS), plan.seatCap)
    }

    // ---------------------------------------------------------------------------------------
    // Laps
    // ---------------------------------------------------------------------------------------

    @Test
    fun lapsAreClampedIntoRange() {
        assertEquals(1, RaceSetupState(laps = 0).effectiveLaps)
        assertEquals(99, RaceSetupState(laps = 500).effectiveLaps)
        assertEquals(3, RaceSetupState(laps = 3).effectiveLaps)
    }

    @Test
    fun everyOfferedLapChoiceIsInRange() {
        assertTrue(LAP_CHOICES.all { it in LAP_RANGE })
    }

    // ---------------------------------------------------------------------------------------
    // Which controls each mode shows - pins the hide-not-disable rule
    // ---------------------------------------------------------------------------------------

    @Test
    fun controlVisibilityPerMode() {
        // mode to (online, choosesFormat, picksTrack, configuresField, usesRoomList, needsName)
        val expected = mapOf<SetupMode, List<Boolean>>(
            SetupMode.LOCAL to listOf(false, true, true, true, false, false),
            SetupMode.HOST to listOf(true, true, true, false, false, true),
            SetupMode.JOIN to listOf(true, false, false, false, true, true),
            SetupMode.SPECTATE to listOf(true, false, false, false, true, false),
        )
        assertEquals(SetupMode.entries.toSet(), expected.keys)

        expected.forEach { (mode, flags) ->
            val actual = listOf(
                mode.isOnline,
                mode.choosesFormat,
                mode.picksTrack,
                mode.configuresField,
                mode.usesRoomList,
                mode.needsPlayerName,
            )
            assertEquals("visibility for $mode", flags, actual)
        }
    }

    @Test
    fun isReversedFromLabelsAnOrientedItineraryEntry() {
        val catalogue = listOf(Tracks.OVAL)
        val plan = RaceSetupState(reversedTracks = setOf(0)).plan(catalogue)

        assertTrue(isReversedFrom(plan.track!!, catalogue))
        assertFalse(isReversedFrom(Tracks.OVAL, catalogue))
    }

    // ---------------------------------------------------------------------------------------
    // AI levels and the gravel trap
    // ---------------------------------------------------------------------------------------

    @Test
    fun aiSeats_carryTheirOwnLevel_andHumansNone() {
        val state = RaceSetupState(
            slots = field(H, A, A, X),
            aiLevels = listOf(AiLevel.BEST, AiLevel.EASY, AiLevel.BEST, AiLevel.NORMAL),
        )
        val configs = state.plan(catalogue).configs
        assertEquals(listOf(false, true, true), configs.map { it.isAi })
        assertEquals(listOf(AiLevel.EASY, AiLevel.EASY, AiLevel.BEST), configs.map { it.aiLevel })
    }

    @Test
    fun aSeatSwitchedToAiAndBack_keepsItsLevel() {
        val chosen = RaceSetupState(slots = field(H, A, X, X), aiLevels = List(4) { AiLevel.BEST })
        val asHuman = chosen.copy(slots = field(H, H, X, X))
        val backToAi = asHuman.copy(slots = field(H, A, X, X))
        assertEquals(AiLevel.BEST, backToAi.plan(catalogue).configs[1].aiLevel)
    }

    @Test
    fun theGravelSwitch_setsTheRules_onTheCurrentRulesVersion() {
        val on = RaceSetupState(slots = field(H, A, X, X), gravelTrap = true).plan(catalogue).rules
        val off = RaceSetupState(slots = field(H, A, X, X), gravelTrap = false).plan(catalogue).rules
        assertEquals(RaceRules.current(gravelTrap = true), on)
        assertEquals(RaceRules.current(gravelTrap = false), off)
    }

    @Test
    fun aFreshForm_hasTheGravelTrapOn_andNormalComputerSeats() {
        val fresh = RaceSetupState()
        assertTrue(fresh.gravelTrap)
        assertEquals(List(MAX_PLAYERS) { AiLevel.NORMAL }, fresh.aiLevels)
    }
}
