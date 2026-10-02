package info.piepgras.gridracer.ui.racesetup

import info.piepgras.gridracer.MAX_TOURNAMENT_TRACKS
import info.piepgras.gridracer.MIN_TOURNAMENT_TRACKS
import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.reversed

/** Cars a race can hold, matching the four player colours and the size of a start grid. */
const val MAX_PLAYERS = 4

/** Below this a track has no race in it - and is never offered, rather than being padded up to it. */
const val MIN_PLAYERS = 2

/** Long enough for a real name, short enough for the name field and a HUD label. */
const val MAX_NAME_LENGTH = 16

/** Offered lap counts. A turn-based race is ~200 turns per player per lap, so this tops out early. */
val LAP_CHOICES = listOf(1, 2, 3)

/** Defended against anyway, so no future control can push a nonsense lap count into a race. */
val LAP_RANGE = 1..99

/**
 * What kind of race is being set up. Local never opens a socket; every other mode does, which
 * is the single fact the connection lifecycle keys off (see GameViewModel.updateRaceSetup).
 */
enum class SetupMode { LOCAL, HOST, JOIN, SPECTATE }

/** A one-off race or a multi-track championship. Only [SetupMode.choosesFormat] modes pick one. */
enum class SetupFormat { SINGLE_RACE, TOURNAMENT }

/**
 * Who is in one of the four seats of a local race. [NONE] is how the field size is chosen -
 * all four seats are always offered, and emptying one is what makes it a three- or two-car
 * race, so there is no separate player-count control to keep in step with the seat rows.
 */
enum class PlayerSlot { HUMAN, AI, NONE }

/** Whether a car starts from this seat - a person or an AI, not an empty seat. */
val PlayerSlot.isRacing: Boolean get() = this != PlayerSlot.NONE

/** Whether this mode needs the multiplayer server. */
val SetupMode.isOnline: Boolean get() = this != SetupMode.LOCAL

/** Joiners and spectators take the format from the room the server puts them in. */
val SetupMode.choosesFormat: Boolean get() = this == SetupMode.LOCAL || this == SetupMode.HOST

/** Modes that pick an existing room rather than describing a new one. */
val SetupMode.usesRoomList: Boolean get() = this == SetupMode.JOIN || this == SetupMode.SPECTATE

/** Spectators are anonymous; everyone else is announced to the room. */
val SetupMode.needsPlayerName: Boolean
    get() = this == SetupMode.HOST || this == SetupMode.JOIN

/** Only modes that describe a new race choose the track it runs on. */
val SetupMode.picksTrack: Boolean
    get() = this == SetupMode.LOCAL || this == SetupMode.HOST

/** Only a local race seats a field of named humans and AI; online the server fills the room. */
val SetupMode.configuresField: Boolean get() = this == SetupMode.LOCAL

/**
 * Why the primary action is refused. The UI maps these to a string resource rather than
 * carrying the message, so the reason stays testable without Android on the classpath.
 */
enum class SetupBlocker {
    NO_TRACKS,
    TRACK_UNRACEABLE,
    NOT_ENOUGH_PLAYERS,
    DUPLICATE_HUMAN_NAMES,
    ITINERARY_TOO_SHORT,
    ITINERARY_TOO_LONG,
    NO_ROOM_CODE,
}

/**
 * Everything the unified race setup screen collects, for every mode. Fields that don't apply
 * to the current [mode]/[format] are simply not rendered (and not read by [plan]), so switching
 * modes and switching back leaves the other mode's answers intact.
 */
data class RaceSetupState(
    /** Local, host, join or spectate. */
    val mode: SetupMode = SetupMode.LOCAL,
    /** Single race or tournament, for the modes that choose one. */
    val format: SetupFormat = SetupFormat.SINGLE_RACE,
    /** Index into the caller's track catalogue; clamped on read, since custom tracks get deleted. */
    val selectedTrackIndex: Int = 0,
    /** Indices of tracks toggled to reversed direction in the picker. Per-track, not global. */
    val reversedTracks: Set<Int> = emptySet(),
    /** Indices of tracks with hazard zones toggled off in the picker. Per-track, not global. */
    val hazardsDisabledTracks: Set<Int> = emptySet(),
    /** Tournament itinerary. Entries are stored already oriented and with hazards baked in. */
    val itinerary: List<Track> = emptyList(),
    /** Host only: how many seats the room offers. A local race counts its [slots] instead. */
    val maxPlayers: Int = MIN_PLAYERS,
    /** Local only: the name for each of the four seats. */
    val names: List<String> = List(MAX_PLAYERS) { defaultPlayerName(it) },
    /** Local only: who fills each of the four seats. */
    val slots: List<PlayerSlot> = List(MAX_PLAYERS) { i ->
        if (i < MIN_PLAYERS) PlayerSlot.HUMAN else PlayerSlot.NONE
    },
    /**
     * Local only: how well the computer drives each of the four seats, read only for seats set to
     * [PlayerSlot.AI]. Kept for every seat, so switching a seat to a person and back keeps its level.
     */
    val aiLevels: List<AiLevel> = List(MAX_PLAYERS) { AiLevel.NORMAL },
    /** Laps per race. */
    val laps: Int = 1,
    /**
     * Local and host: whether the race is played with the gravel trap on. For a tournament it
     * applies to every race of it.
     */
    val gravelTrap: Boolean = true,
    /** Join and spectate: the code of the room to enter. */
    val roomCode: String = "",
    /**
     * Host: set to make the room private. Join: the password being offered. Blank means a
     * public room either way.
     */
    val roomPassword: String = "",
)

/**
 * Everything derived from a [RaceSetupState] plus the live track catalogue. Pure, so the
 * whole validation surface of the setup screen is unit-testable without Compose.
 */
data class RaceSetupPlan(
    /** Already oriented. Null when this mode picks no track, or the catalogue is empty. */
    val track: Track?,
    /** The tracks to race, oriented: one for a single race, the whole list for a tournament. */
    val itinerary: List<Track>,
    /** How many cars the chosen track (or the narrowest one in the itinerary) can seat. 0 = none. */
    val seatCap: Int,
    /** How many cars will actually start, after seats the track cannot hold are emptied. */
    val effectivePlayerCount: Int,
    /**
     * The four seats as they will actually race, with any seat the current track can't hold
     * emptied. Derived rather than written back, so narrowing the track and widening it again
     * restores the field the user had set up.
     */
    val slots: List<PlayerSlot>,
    /** Local modes only - online the server builds the field. */
    val configs: List<PlayerConfig>,
    /** The rules the race (or every race of the tournament) is played under. */
    val rules: RaceRules,
    /** The first thing stopping the form from being submitted, or null when it can be. */
    val blocker: SetupBlocker?,
) {
    /** Whether the form is complete and the race can start. */
    val canSubmit: Boolean get() = blocker == null

    /**
     * The colour a seat races in: [PlayerColors] is indexed by position in the field, so
     * emptying seat 2 moves seat 3 up into its colour.
     */
    fun raceIndexOf(slot: Int): Int = slots.take(slot).count { it.isRacing }
}

/** The placeholder a blank name slot falls back to, and the value the field is seeded with. */
fun defaultPlayerName(index: Int): String = "Player ${index + 1}"

/** Trims a typed name to [MAX_NAME_LENGTH] characters. */
fun sanitizeName(raw: String): String = raw.take(MAX_NAME_LENGTH)

/**
 * Whether [track] is the reverse of the catalogue entry of the same name - used to label
 * itinerary chips, which hold oriented tracks and so can't be compared by identity.
 */
fun isReversedFrom(track: Track, catalogue: List<Track>): Boolean {
    val original = catalogue.firstOrNull { it.name == track.name } ?: return false
    return original.finishDirection != track.finishDirection
}

/**
 * Resolves a setup state against the current track catalogue: orients the chosen track,
 * works out how many cars fit, and decides whether the race can start.
 *
 * This is the one place the seat cap is computed. It used to be four places - the local race
 * screen, the local tournament screen, the online host form and the online tournament host
 * form - which disagreed about whether [MAX_PLAYERS] applied at all.
 */
fun RaceSetupState.plan(allTracks: List<Track>): RaceSetupPlan {
    val laps = laps.coerceIn(LAP_RANGE)
    val tournament = mode.choosesFormat && format == SetupFormat.TOURNAMENT

    val emptyField = List(MAX_PLAYERS) { PlayerSlot.NONE }

    val rules = RaceRules.current(gravelTrap = gravelTrap)

    // Room-code modes describe no race of their own; the room they join defines all of it.
    if (mode.usesRoomList) {
        return RaceSetupPlan(
            track = null,
            itinerary = emptyList(),
            seatCap = 0,
            effectivePlayerCount = 0,
            slots = emptyField,
            configs = emptyList(),
            rules = rules,
            blocker = if (roomCode.isBlank()) SetupBlocker.NO_ROOM_CODE else null,
        )
    }

    if (allTracks.isEmpty()) {
        return RaceSetupPlan(null, emptyList(), 0, 0, emptyField, emptyList(), rules, SetupBlocker.NO_TRACKS)
    }

    val track = if (mode.picksTrack) {
        // Deleting a custom track shortens the list under an index from a previous visit.
        val safeIndex = selectedTrackIndex.coerceIn(allTracks.indices)
        val base = allTracks[safeIndex]
        // Reversing rebuilds the start grid for the new direction, so it has to happen before
        // the seat cap below is read off it - see Track.reversed().
        val isReversed = safeIndex in reversedTracks
        val oriented = if (isReversed) base.reversed() else base
        // Strip modifier zones when this track's hazard toggle is off. Start positions are unaffected.
        val hazardsOn = safeIndex !in hazardsDisabledTracks
        if (hazardsOn) oriented else oriented.copy(modifiers = emptyList())
    } else {
        null
    }

    // A track's start box only fits as many cars as it has start positions - a narrow gate
    // supports fewer than the usual 4. Capping only the upper end is deliberate: a track is
    // expected to have at least MIN_PLAYERS, so this must not silently float a lower count
    // up to 2 and hide a track that is actually unraceable.
    val rawCap = when {
        tournament -> itinerary.minOfOrNull { it.startPositions.size } ?: MAX_PLAYERS
        track != null -> track.startPositions.size
        else -> MAX_PLAYERS
    }
    val seatCap = rawCap.coerceAtMost(MAX_PLAYERS)

    // Empty any seat past what this track can hold, latest first, so switching to a narrow
    // track drops the back of the grid rather than silently racing a field that won't fit.
    var seatsLeft = seatCap
    val effectiveSlots = (0 until MAX_PLAYERS).map { i ->
        val slot = slots.getOrElse(i) { PlayerSlot.NONE }
        if (slot.isRacing && seatsLeft > 0) {
            seatsLeft--
            slot
        } else {
            PlayerSlot.NONE
        }
    }

    val nameFor = { i: Int -> names.getOrNull(i)?.ifBlank { defaultPlayerName(i) } ?: defaultPlayerName(i) }
    val configs = if (mode.configuresField) {
        effectiveSlots.mapIndexedNotNull { i, slot ->
            if (!slot.isRacing) return@mapIndexedNotNull null
            val isAi = slot == PlayerSlot.AI
            PlayerConfig(
                name = nameFor(i),
                isAi = isAi,
                aiLevel = if (isAi) aiLevels.getOrElse(i) { AiLevel.NORMAL } else AiLevel.EASY,
            )
        }
    } else {
        emptyList()
    }

    // A local race is exactly the seats that are filled; a hosted room is sized up front and
    // the server fills the rest of it.
    val effectivePlayerCount =
        if (mode.configuresField) configs.size else maxPlayers.coerceIn(MIN_PLAYERS, seatCap.coerceAtLeast(MIN_PLAYERS))

    // Only enforced locally: online you supply one name and the server assigns the rest, so a
    // collision there isn't yours to fix and the error would be unclearable.
    val duplicateHumanNames = mode.configuresField &&
        configs.filterNot { it.isAi }.map { it.name }
            .let { humans -> humans.size != humans.distinct().size }

    val blocker = when {
        tournament && itinerary.size < MIN_TOURNAMENT_TRACKS -> SetupBlocker.ITINERARY_TOO_SHORT
        tournament && itinerary.size > MAX_TOURNAMENT_TRACKS -> SetupBlocker.ITINERARY_TOO_LONG
        seatCap < MIN_PLAYERS -> SetupBlocker.TRACK_UNRACEABLE
        mode.configuresField && configs.size < MIN_PLAYERS -> SetupBlocker.NOT_ENOUGH_PLAYERS
        duplicateHumanNames -> SetupBlocker.DUPLICATE_HUMAN_NAMES
        else -> null
    }

    return RaceSetupPlan(
        track = track,
        // Itinerary entries already have direction and hazards baked in at add-time.
        itinerary = if (tournament) itinerary else emptyList(),
        seatCap = seatCap,
        effectivePlayerCount = effectivePlayerCount,
        slots = effectiveSlots,
        configs = configs,
        rules = rules,
        blocker = blocker,
    )
}

/** The lap count actually raced, clamped the same way [plan] clamps it. */
val RaceSetupState.effectiveLaps: Int get() = laps.coerceIn(LAP_RANGE)
