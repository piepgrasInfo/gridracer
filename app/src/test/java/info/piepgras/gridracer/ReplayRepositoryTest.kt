package info.piepgras.gridracer

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.replay.MoveRecord
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.replay.isPlayable
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers replay identity and the guard on an imported file.
 *
 * A replay used to be identified by its `timestamp`, which is not an identity: two races
 * finishing in the same millisecond collide, and a file imported from a server carries whatever
 * timestamp that server wrote. Both matter now that replays can arrive from outside the device.
 */
class ReplayRepositoryTest {

    private lateinit var storageFile: File
    private lateinit var repository: ReplayRepository

    private fun replay(track: String = "Oval", id: String = "", timestamp: Long = 0L): ReplayData {
        val engineTrack = Tracks.all.first()
        return ReplayData(
            trackName = track,
            configs = listOf(PlayerConfig("A", isAi = false), PlayerConfig("B", isAi = true)),
            initialState = GameEngine.newGame(engineTrack, engineTrack.startPositions.take(2), totalLaps = 1),
            moves = emptyList(),
            timestamp = timestamp,
            id = id,
        )
    }

    @Before
    fun setUp() {
        storageFile = File.createTempFile("replays", ".json")
        storageFile.delete()
        repository = ReplayRepository(storageFile, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    @Test
    fun savingAssignsAnIdAndATimestamp() {
        val stored = repository.save(replay())

        assertTrue("a stored replay needs a stable id", stored.id.isNotBlank())
        assertNotEquals("a stored replay needs a real timestamp", 0L, stored.timestamp)
    }

    @Test
    fun twoReplaysSavedInTheSameMillisecondAreDistinct() {
        // The old identity was the timestamp, so these two collided and the second silently
        // replaced the first in the list - and deleting one deleted both.
        val now = 1_700_000_000_000L
        val first = repository.save(replay(timestamp = now))
        val second = repository.save(replay(timestamp = now))

        assertNotEquals(first.id, second.id)
        assertEquals(2, repository.replays.value.size)
    }

    @Test
    fun deletingRemovesOnlyThatReplay() {
        val first = repository.save(replay(track = "Oval"))
        val second = repository.save(replay(track = "Square"))

        repository.delete(first)

        assertEquals(listOf(second.id), repository.replays.value.map { it.id })
    }

    @Test
    fun savingTheSameReplayTwiceReplacesRatherThanDuplicates() {
        // Re-importing a file you already have must not leave two copies.
        val stored = repository.save(replay(id = "fixed-id"))
        repository.save(stored)

        assertEquals(1, repository.replays.value.size)
    }

    @Test
    fun containsFindsAStoredReplayById() {
        val stored = repository.save(replay(id = "from-a-server"))

        assertTrue(repository.contains(stored.id))
        assertFalse(repository.contains("never-seen"))
    }

    @Test
    fun replaysWrittenBeforeIdsExistedGetOneOnLoad() {
        // A file from an older build has no id at all; the list keys off it from the first
        // frame, so one is assigned when it is read rather than the first time it is saved.
        storageFile.writeText(
            """[{"trackName":"Oval","configs":[],"initialState":${initialStateJson()},"moves":[],"timestamp":123}]""",
        )
        val reloaded = ReplayRepository(storageFile, Dispatchers.Unconfined)

        assertEquals(1, reloaded.replays.value.size)
        assertTrue(reloaded.replays.value.single().id.isNotBlank())
        assertEquals("an existing timestamp is kept", 123L, reloaded.replays.value.single().timestamp)
    }

    // -----------------------------------------------------------------------------------
    // What counts as an importable file
    // -----------------------------------------------------------------------------------

    @Test
    fun aWellFormedReplayIsPlayable() {
        assertTrue(replay().isPlayable)
    }

    @Test
    fun aReplayWhoseConfigsDoNotMatchItsPlayersIsNotPlayable() {
        // The race screen labels players by seat, so a mismatched roster indexes off the end.
        assertFalse(replay().copy(configs = listOf(PlayerConfig("only one", isAi = false))).isPlayable)
    }

    @Test
    fun aReplayWithAMoveForANonExistentPlayerIsNotPlayable() {
        // A hand-edited or truncated file. Playing it would throw on that move.
        val bad = replay().copy(
            moves = listOf(MoveRecord(playerId = 9, target = info.piepgras.gridracer.engine.Point(1, 1), randomSeed = 0L)),
        )
        assertFalse(bad.isPlayable)
    }

    private fun initialStateJson(): String {
        val track = Tracks.all.first()
        val state = GameEngine.newGame(track, track.startPositions.take(2), totalLaps = 1)
        return kotlinx.serialization.json.Json.encodeToString(
            info.piepgras.gridracer.engine.GameState.serializer(),
            state,
        )
    }
}
