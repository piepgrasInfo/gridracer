package info.piepgras.gridracer.server.db

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaderboardDaoTest {

    private lateinit var db: Database
    private val dao = LeaderboardDao()
    private val accountDao = AccountDao()

    @Before
    fun setUp() {
        db = Database(":memory:")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun registerAccount(username: String, displayName: String = username): Long {
        val account = (info.piepgras.gridracer.server.db.AuthService(db)
            .register(username, "password1", displayName) as AuthService.Result.Ok).success.account
        return account.id
    }

    @Test
    fun anEmptyLeaderboardReturnsZeroEntries() = runBlocking {
        val page = db.read { dao.page(it, offset = 0, limit = 50, forAccountId = null) }
        assertEquals(0, page.entries.size)
        assertEquals(0, page.totalEntries)
        assertNull(page.you)
    }

    @Test
    fun registeredAccountsAppearOnTheLeaderboard() = runBlocking {
        registerAccount("alice", "Alice")
        registerAccount("bobby", "Bobby")
        val page = db.read { dao.page(it, offset = 0, limit = 50, forAccountId = null) }
        assertEquals(2, page.entries.size)
        assertEquals(2, page.totalEntries)
        assertTrue(page.entries.all { it.rating == Elo.STARTING_RATING })
        assertTrue(page.entries.all { it.matches == 0 })
    }

    @Test
    fun theLeaderboardIsOrderedByRatingDescending() = runBlocking {
        val alice = registerAccount("alice", "Alice")
        val bob = registerAccount("bobby", "Bobby")
        // Alice wins a race against Bob.
        db.write {
            dao.applyRaceResult(it, mapOf(alice to 10, bob to -10), setOf(alice))
        }
        val page = db.read { dao.page(it, offset = 0, limit = 50, forAccountId = null) }
        assertEquals("Alice should be ranked first after winning", "Alice", page.entries[0].displayName)
        assertEquals(1, page.entries[0].rank)
        assertEquals(2, page.entries[1].rank)
    }

    @Test
    fun applyRaceResultUpdatesMatchesAndWins() = runBlocking {
        val alice = registerAccount("alice", "Alice")
        val bob = registerAccount("bobby", "Bobby")
        db.write {
            dao.applyRaceResult(it, mapOf(alice to 10, bob to -10), setOf(alice))
        }
        val page = db.read { dao.page(it, offset = 0, limit = 50, forAccountId = null) }
        val aliceEntry = page.entries.first { it.displayName == "Alice" }
        val bobEntry = page.entries.first { it.displayName == "Bobby" }
        assertEquals(1, aliceEntry.matches)
        assertEquals(1, aliceEntry.wins)
        assertEquals(1, bobEntry.matches)
        assertEquals(0, bobEntry.wins)
    }

    @Test
    fun theCallersOwnRowIsReturnedWithAbsoluteRank() = runBlocking {
        val alice = registerAccount("alice", "Alice")
        val bob = registerAccount("bobby", "Bobby")
        db.write {
            dao.applyRaceResult(it, mapOf(alice to 10, bob to -10), setOf(alice))
        }
        // Bob asks for the leaderboard; his own row should come back with rank 2.
        val page = db.read { dao.page(it, offset = 0, limit = 1, forAccountId = bob) }
        val you = page.you
        assertNotNull(you)
        assertEquals(2, you!!.rank)
        assertEquals("Bobby", you.displayName)
    }

    @Test
    fun paginationRespectsOffsetAndLimit() = runBlocking {
        for (i in 1..5) registerAccount("user$i", "User$i")
        val page = db.read { dao.page(it, offset = 2, limit = 2, forAccountId = null) }
        assertEquals(2, page.entries.size)
        assertEquals(5, page.totalEntries)
        assertEquals(3, page.entries[0].rank)
        assertEquals(4, page.entries[1].rank)
    }
}
