package info.piepgras.gridracer.engine.planner

/**
 * Every knob that sets how well the computer drives, in one place.
 *
 * The three levels differ along four independent axes, and these are the dials to turn when one of
 * them plays too strong or too weak:
 *
 * 1. **How it decides.** [info.piepgras.gridracer.engine.AiLevel.EASY] scores moves with a heuristic
 *    (progress round the track's centre, a safety margin, a mild dislike of hazards) and never plans
 *    a route. NORMAL and BEST look every move up in an exact table of how many moves the rest of the
 *    race takes from the state it leads to ([RacePlanner]), so they never drive into a corner they
 *    cannot get out of and never waste a move on the wrong line.
 * 2. **How fast it lets itself go** - [NORMAL_MAX_SPEED], [BEST_MAX_SPEED]. The cap is built into
 *    the table, not applied afterwards: the table then describes the best race *at that speed*, so a
 *    capped driver brakes for corners at the right moment instead of discovering the cap too late.
 * 3. **How often it slips** - [NORMAL_MISTAKE_PROBABILITY] and [NORMAL_MISTAKE_SLACK]. Now and then
 *    NORMAL takes a move that costs it a little, never one the table says leads to a crash.
 * 4. **Where it starts** - [NORMAL_BEST_SLOT_PROBABILITY]. BEST always takes the start slot with the
 *    shortest race ahead of it; NORMAL usually does; EASY takes any.
 *
 * **Measured** (2026-10-13; solo 3-lap races with the gravel trap on, 24 seeds per track, mean
 * moves): Oval Easy 93.6 / Normal 60.1 / Best 55.1, Square 62.0 / 58.8 / 53.7, Speedway
 * 83.5 / 73.6 / 66.0, Triangle 102.0 / 55.7 / 52.0, a hand-drawn track 98.3 / 39.7 / 36.7. The
 * slip rate is what places NORMAL: at 0.25 it was no faster than EASY on the Square, at 0.15 it
 * sits between the two everywhere. The speed cap hardly mattered on tracks this size - their
 * corners hold every driver to 4 or less - but it would on long straights. EASY is the most
 * uneven of the three because it drifts into the gravel and loses the braked moves.
 *
 * Oil is the one thing the table cannot know in advance - where the car slides is drawn when it
 * hits the spill. The table models a spill by its average effect: the slip offsets average to zero,
 * so on average the car coasts straight on, and that is what the table assumes. It neither steers
 * round spills nor drives at them; it just counts the turn of steering it loses.
 */
object AiTuning {

    /** NORMAL's top speed per axis. Most tracks' corners want 2-4; straights reward more. */
    const val NORMAL_MAX_SPEED = 4

    /**
     * BEST's top speed per axis: high enough never to bind on a track the editor can draw - a car
     * needs about 78 units of straight to reach it - while keeping the table bounded on a huge one.
     */
    const val BEST_MAX_SPEED = 12

    /** How often NORMAL takes a slightly worse move instead of the best one. */
    const val NORMAL_MISTAKE_PROBABILITY = 0.15

    /** How much worse that move may be, in moves still to drive. */
    const val NORMAL_MISTAKE_SLACK = 1

    /** How often NORMAL takes the best start slot rather than a random one. */
    const val NORMAL_BEST_SLOT_PROBABILITY = 0.75

    /**
     * How many laps the table is worked out for exactly. Beyond this the driver assumes each further
     * lap takes as long as the last one worked out, which holds once a racing line repeats - a test
     * checks that it does on every built-in track.
     */
    const val LAP_LAYERS = 4

    /**
     * The largest table built, in states (position, velocity, gravel memory). Every built-in track
     * needs well under a tenth of this. A track too large for it is raced by the EASY driver instead.
     */
    const val MAX_NODES = 600_000

    /** The planning profile behind NORMAL. */
    val NORMAL_PROFILE = PlannerProfile(maxSpeed = NORMAL_MAX_SPEED)

    /** The planning profile behind BEST. */
    val BEST_PROFILE = PlannerProfile(maxSpeed = BEST_MAX_SPEED)
}

/**
 * What a planning table is built for, beyond the track and the rules.
 *
 * @property maxSpeed The fastest a car may go on either axis in the planned race.
 */
data class PlannerProfile(val maxSpeed: Int)
