package info.piepgras.gridracer.ui

import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Bugsink crash, reduced to an assertion.
 *
 * A player with two custom tracks both named "mine" crashed the lobby's track picker the
 * moment the list scrolled:
 *
 * ```
 * IllegalArgumentException: Key "mine" was already used.
 * If you are using LazyColumn/Row please make sure you provide a unique key for each item.
 * ```
 *
 * The picker had been keyed on `track.name`. Names are typed by the user and nothing makes
 * them unique - not in the editor, not on download, not on import. A `LazyColumn` key must be
 * unique across the list or Compose throws during measure, so the only thing that can be a key
 * here is something the *list* guarantees: the position.
 *
 * There is no Compose UI test harness in this project, so this asserts the property directly
 * rather than the screen. It is the property that matters: whatever the key function is, it
 * must not collide.
 */
class TrackPickerKeyTest {

    private fun named(name: String): Track = Tracks.OVAL.copy(name = name)

    /** What the lobby actually hands the picker: built-ins plus the user's own tracks. */
    private fun keysFor(tracks: List<Track>): List<Any> =
        tracks.mapIndexed { index, track -> trackPickerKey(index, track) }

    @Test
    fun twoTracksWithTheSameNameGetDifferentKeys() {
        val keys = keysFor(listOf(named("mine"), named("mine")))

        assertEquals("both rows must be addressable", 2, keys.toSet().size)
    }

    @Test
    fun aWholeListOfIdenticallyNamedTracksStillHasUniqueKeys() {
        val keys = keysFor(List(8) { named("mine") })

        assertEquals(8, keys.toSet().size)
    }

    @Test
    fun theRealisticListIsUnique() {
        // Built-ins, plus a custom track that shadows a built-in's name, plus the two that
        // caused the crash. This is the shape `allTracksForLobby` produces.
        val tracks = Tracks.all + listOf(named("Oval"), named("mine"), named("mine"))

        val keys = keysFor(tracks)

        assertEquals(tracks.size, keys.toSet().size)
    }

    @Test
    fun anEmptyListProducesNoKeys() {
        assertEquals(0, keysFor(emptyList()).size)
    }
}
