package info.piepgras.gridracer.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {

    @Test
    fun pointsForRank_followsTheF1StyleTable() {
        assertEquals(10, Scoring.pointsForRank(1))
        assertEquals(6, Scoring.pointsForRank(2))
        assertEquals(4, Scoring.pointsForRank(3))
        assertEquals(2, Scoring.pointsForRank(4))
        assertEquals(0, Scoring.pointsForRank(5))
        assertEquals(0, Scoring.pointsForRank(null)) // crashed / DNF
    }

    @Test
    fun recordResult_createsAndAccumulatesAnEntryByName() {
        var board = Scoring.recordResult(emptyList(), "Ada", 1)
        assertEquals(listOf(LeaderboardEntry("Ada", points = 10, races = 1, wins = 1)), board)

        board = Scoring.recordResult(board, "Ada", 3)
        assertEquals(listOf(LeaderboardEntry("Ada", points = 14, races = 2, wins = 1)), board)
    }

    @Test
    fun recordResult_sortsDescendingByPoints() {
        var board = Scoring.recordResult(emptyList(), "Ada", 4) // 2 pts
        board = Scoring.recordResult(board, "Grace", 1) // 10 pts
        board = Scoring.recordResult(board, "Alan", null) // 0 pts, crashed

        assertEquals(listOf("Grace", "Ada", "Alan"), board.map { it.name })
    }
}
