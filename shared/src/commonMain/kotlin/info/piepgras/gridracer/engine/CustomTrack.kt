package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * A user-created track, saved together with both the raw [draft] (so the editor can
 * re-open it for editing) and the already-compiled [track] (so play never has to
 * re-run [TrackBuilder]/[TrackValidator] at load time - [Track.walls] alone can't be
 * decomposed back into outer/inner/obstacle groups, so the draft has to be kept too).
 */
@Serializable
data class CustomTrack(
    /** Stable local identifier, unique per device; the file and list key for this track. */
    val id: String,
    /** Display name the user gave the track. */
    val name: String,
    /** The editable source shape, kept so the editor can reopen the track. */
    val draft: TrackDraft,
    /** The compiled, playable form of [draft]. */
    val track: Track,
    /**
     * The server-side track id when this track was downloaded from a shared-track server,
     * or null for tracks the user created locally. Used to deduplicate downloads: if a track
     * with the same [sharedTrackId] already exists in the library, the download is a no-op
     * rather than creating a second local copy.
     */
    val sharedTrackId: String? = null,
)
