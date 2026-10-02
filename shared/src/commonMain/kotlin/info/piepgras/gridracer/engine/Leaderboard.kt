package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * One name's running total on the local leaderboard.
 *
 * @property name The player name the results are recorded under; entries are keyed by it.
 * @property points Total points from [Scoring.pointsForRank] across every race recorded.
 * @property races Number of races recorded.
 * @property wins Number of those races finished first.
 */
@Serializable
data class LeaderboardEntry(
    val name: String,
    val points: Int = 0,
    val races: Int = 0,
    val wins: Int = 0,
)

/** F1-style scoring: consistent good finishes beat a single lucky win. */
object Scoring {

    /**
     * Points for one finishing position: 10, 6, 4 and 2 for the top four, nothing after that.
     *
     * @param rank The 1-based finishing position, or null for a crash or a race not finished.
     * @return The points earned; never negative.
     */
    fun pointsForRank(rank: Int?): Int = when (rank) {
        1 -> 10
        2 -> 6
        3 -> 4
        4 -> 2
        else -> 0 // crashed, or finished worse than 4th
    }

    /** Upserts [name]'s entry with the result of one race; returns the list sorted by points desc. */
    fun recordResult(current: List<LeaderboardEntry>, name: String, rank: Int?): List<LeaderboardEntry> {
        val points = pointsForRank(rank)
        val won = rank == 1
        val existing = current.firstOrNull { it.name == name }
        val updated = if (existing != null) {
            existing.copy(
                points = existing.points + points,
                races = existing.races + 1,
                wins = existing.wins + if (won) 1 else 0,
            )
        } else {
            LeaderboardEntry(name = name, points = points, races = 1, wins = if (won) 1 else 0)
        }
        return (current.filterNot { it.name == name } + updated).sortedByDescending { it.points }
    }
}
