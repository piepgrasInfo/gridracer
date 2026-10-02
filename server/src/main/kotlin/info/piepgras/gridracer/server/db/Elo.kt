package info.piepgras.gridracer.server.db

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure Elo rating maths, lifted out of the old `RatingsManager` so the leaderboard can run
 * against the database without re-deriving the formula.
 *
 * Every function is total and side-effect free; the only state is what the caller passes in.
 * A "race" here is a list of finishers keyed by a stable id, each with a finish rank (1-based)
 * or null for a DNF/crash. The pairwise expected-score loop is the standard one: every pair
 * contributes a delta for the winner and the opposite for the loser, ties split 0.5/0.5, and
 * a DNF loses to every finisher and ties another DNF.
 */
object Elo {

    /** One participant in a race to be rated. */
    data class Finisher(
        /** Stable key - an account id in production, any comparable id in tests. */
        val id: Long,
        /** 1-based finish rank, or null for a DNF/crash. */
        val finishRank: Int?,
    )

    /** The K-factor. 32 is the classic value and what the old RatingsManager used. */
    const val DEFAULT_K = 32.0

    /** The starting rating for a new account. */
    const val STARTING_RATING = 1000

    /**
     * Computes the rating delta for each finisher in a race.
     *
     * Returns a map keyed by [Finisher.id]; a finisher not in the map (none, in practice)
     * would have a zero delta. The deltas sum to zero across all finishers, since every pair
     * contributes `+delta` to one and `-delta` to the other.
     */
    fun deltas(
        finishers: List<Finisher>,
        ratingsById: Map<Long, Int>,
        k: Double = DEFAULT_K,
    ): Map<Long, Int> {
        if (finishers.size < 2) return emptyMap()
        val changes = mutableMapOf<Long, Double>()
        for (i in finishers.indices) {
            for (j in i + 1 until finishers.size) {
                val a = finishers[i]
                val b = finishers[j]
                val ratingA = ratingsById[a.id] ?: STARTING_RATING
                val ratingB = ratingsById[b.id] ?: STARTING_RATING
                val expectedA = 1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / 400.0))
                val outcomeA = outcome(a.finishRank, b.finishRank)
                val delta = k * (outcomeA - expectedA)
                changes[a.id] = changes.getOrDefault(a.id, 0.0) + delta
                changes[b.id] = changes.getOrDefault(b.id, 0.0) - delta
            }
        }
        return changes.mapValues { (_, v) -> v.roundToInt() }
    }

    /** The score for `a` against `b`: 1 for a win, 0 for a loss, 0.5 for a tie. */
    fun outcome(rankA: Int?, rankB: Int?): Double = when {
        rankA == null && rankB == null -> 0.5
        rankA == null -> 0.0
        rankB == null -> 1.0
        rankA < rankB -> 1.0
        rankA > rankB -> 0.0
        else -> 0.5
    }
}
