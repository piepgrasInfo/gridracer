package info.piepgras.gridracer.server.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EloTest {

    @Test
    fun twoEqualRatedPlayersSplitZeroDeltaOnATie() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = 1),
        )
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000, 2L to 1000))
        // A tie between equal-rated players: expected = 0.5, outcome = 0.5, delta = 0.
        assertEquals(0, deltas[1])
        assertEquals(0, deltas[2])
    }

    @Test
    fun theWinnerGainsAndTheLoserLosesTheSameAmount() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = 2),
        )
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000, 2L to 1000))
        val gain = deltas[1]!!
        val loss = deltas[2]!!
        assertTrue("winner should gain rating", gain > 0)
        assertEquals("deltas sum to zero", -loss, gain)
    }

    @Test
    fun aDnfLosesToAFinisher() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = null),
        )
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000, 2L to 1000))
        assertTrue("finisher gains against DNF", deltas[1]!! > 0)
        assertTrue("DNF loses against finisher", deltas[2]!! < 0)
    }

    @Test
    fun twoDnfsTie() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = null),
            Elo.Finisher(id = 2, finishRank = null),
        )
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000, 2L to 1000))
        assertEquals(0, deltas[1])
        assertEquals(0, deltas[2])
    }

    @Test
    fun aHigherRatedWinnerGainsLessThanAnUpset() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = 2),
        )
        // Favorite wins: rating 1200 beats 800.
        val favorite = Elo.deltas(finishers, mapOf(1L to 1200, 2L to 800))
        // Upset: rating 800 beats 1200.
        val upset = Elo.deltas(finishers, mapOf(1L to 800, 2L to 1200))
        assertTrue(
            "an upset should gain more than a favorite winning: ${upset[1]} vs ${favorite[1]}",
            upset[1]!! > favorite[1]!!,
        )
    }

    @Test
    fun aSingleFinisherProducesNoDelta() {
        val finishers = listOf(Elo.Finisher(id = 1, finishRank = 1))
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun anUnknownIdUsesStartingRating() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = 2),
        )
        // Player 2 is not in the ratings map; should default to STARTING_RATING (1000).
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000))
        // Equal ratings (1000 vs default 1000), winner gains.
        assertTrue(deltas[1]!! > 0)
        assertTrue(deltas[2]!! < 0)
    }

    @Test
    fun threePlayerRaceDeltasSumToZero() {
        val finishers = listOf(
            Elo.Finisher(id = 1, finishRank = 1),
            Elo.Finisher(id = 2, finishRank = 2),
            Elo.Finisher(id = 3, finishRank = 3),
        )
        val deltas = Elo.deltas(finishers, mapOf(1L to 1000, 2L to 1100, 3L to 900))
        val sum = deltas.values.sum()
        assertEquals("deltas across all finishers sum to zero", 0, sum)
    }
}
