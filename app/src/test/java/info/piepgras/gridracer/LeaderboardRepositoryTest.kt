package info.piepgras.gridracer

import info.piepgras.gridracer.engine.LeaderboardEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaderboardRepositoryTest {

    private lateinit var storageFile: File

    @Before
    fun setUp() {
        storageFile = File.createTempFile("leaderboard", ".json")
        storageFile.delete()
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    // Dispatchers.Unconfined runs the launched save() inline on the calling thread,
    // so assertions can check the file right after recordRace() returns.
    private fun repository() = LeaderboardRepository(storageFile, Dispatchers.Unconfined)

    @Test
    fun recordRace_persistsResultsToDisk() {
        repository().recordRace(listOf("Ada" to 1))

        assertTrue(storageFile.exists())
        val reloaded = repository().entries.value
        assertEquals(1, reloaded.size)
        assertEquals("Ada", reloaded[0].name)
        assertEquals(10, reloaded[0].points)
    }

    @Test
    fun recordRace_accumulatesAcrossMultipleCalls() {
        val repo = repository()
        repo.recordRace(listOf("Ada" to 1))
        repo.recordRace(listOf("Ada" to 3))

        val entry = repo.entries.value.single { it.name == "Ada" }
        assertEquals(14, entry.points)
        assertEquals(2, entry.races)
    }

    @Test
    fun recordRace_keepsFullHistoryBeyondTopTen() {
        val repo = repository()
        for (i in 1..12) {
            repo.recordRace(listOf("Player$i" to 1))
        }

        assertEquals(12, repo.entries.value.size)
        assertEquals(12, repository().entries.value.size)
    }

    @Test
    fun entries_withMissingFile_isEmpty() {
        assertEquals(emptyList<LeaderboardEntry>(), repository().entries.value)
    }

    @Test
    fun entries_withCorruptFile_isEmptyInsteadOfCrashing() {
        storageFile.writeText("not valid json")
        assertEquals(emptyList<LeaderboardEntry>(), repository().entries.value)
    }

    @Test
    fun recordRace_doesNotThrowWhenTheFileCannotBeWritten() {
        val unwritableTarget = File(storageFile.parentFile, "leaderboard-dir-${System.nanoTime()}").apply { mkdir() }
        try {
            val repo = LeaderboardRepository(unwritableTarget, Dispatchers.Unconfined)
            repo.recordRace(listOf("Ada" to 1))
            // The write to disk fails (it's a directory, not a file), but the in-memory
            // state must still update rather than the whole call throwing.
            assertEquals(1, repo.entries.value.size)
        } finally {
            unwritableTarget.delete()
        }
    }
}
