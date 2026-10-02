package info.piepgras.gridracer.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random

/**
 * A simple opponent. It takes a move that finishes the race when one is available;
 * otherwise it scores every legal move and takes the best, on five counts:
 *
 * - **Safety**, primarily from a precomputed *safety set* (see [computeSafeSet]): when the
 *   player is unaffected by modifiers, the AI restricts itself to moves that land in a
 *   state from which it can avoid crashing into a wall indefinitely. This is a true winning
 *   region, not a look-ahead approximation, so on clean track the AI cannot crash. Under a
 *   modifier (or from a state the safety set doesn't cover, e.g. still fast right after a
 *   spill) it falls back to a three-ply look-ahead - see [outlookFor].
 * - **Progress** around the track, which is what keeps races finishing instead of the AI
 *   wandering a loop forever without ever crashing *or* winning.
 * - **Hazards entered**, as a mild cost. Mild on purpose: a spill it can handle should not
 *   outweigh a clearly better line, and treating hazards as walls is the behaviour this
 *   replaced.
 * - **Speed**, capped at [MAX_COMFORT_SPEED] by the narrowing pass so the AI doesn't
 *   accelerate hard into a corner it can't steer out of, and used again as a small
 *   tie-break among equally-safe, equally-progressing moves so it doesn't crawl on safe
 *   straights.
 *
 * It also never coasts to a stop while any other move exists - standing still is safe, makes
 * no progress, and reads as an opponent that has given up.
 */
object SimpleAi {

    /**
     * Top speed (per axis) the AI will choose while it has any alternative. The safety set is
     * computed over this same velocity range, so a move the AI is willing to pick is always
     * one the safety set can pass judgement on. 3 lets the AI carry meaningful speed on
     * straights and finish fast; the safety set, not a look-ahead depth, is what now stops it
     * running that speed into a corner it can't steer out of.
     */
    private const val MAX_COMFORT_SPEED = 3

    /**
     * What a hazard is worth in units of progress, where progress across the surviving
     * candidates is normalised to [0,1]. Deliberately well under 1: a clearly better line
     * through a spill should beat a poor line around it, and only a near-tie is settled by
     * staying clean. Steering round hazards is a preference; the safety filter below is what
     * stops the AI entering one it cannot get out of.
     */
    private const val HAZARD_COST = 0.25

    /** Scores within this of the best count as tied, and are chosen between at random. */
    private const val TIE_EPSILON = 1e-9

    /**
     * How much a unit of speed is worth as a tie-break among otherwise-equal moves, in units
     * of progress (which is normalised to [0,1]). Small on purpose: it only decides between
     * moves whose progress is already within [TIE_EPSILON], so it can never override a
     * genuinely better line - it just stops the AI from crawling on safe straights by
     * preferring the faster of two equally-good, equally-safe options.
     */
    private const val SPEED_TIE_BREAK = 0.05

    /**
     * How many recent path positions are checked for revisits. Long enough to break A-B-A-B
     * oscillation and small loops, short enough not to trap the AI on a track it has already
     * been around once (a lap is many more positions than this).
     */
    private const val RECENT_PATH_WINDOW = 10

    /**
     * The penalty for revisiting a position, scaled by recency. A position visited 1 move ago
     * gets the full penalty; one visited [RECENT_PATH_WINDOW] moves ago gets ~0. The penalty
     * is well above 1.0 (the progress range) so it overrides angular progress and forces the
     * AI to pick a different move, breaking oscillation cycles.
     */
    private const val REVISIT_PENALTY = 3.0

    /**
     * Per-axis bound on the velocity a state in the safety set can carry. Matches
     * [MAX_COMFORT_SPEED]: the AI only picks moves whose resulting speed is at most that, so
     * the safety set is computed over the same velocity range it will actually query. A state
     * whose velocity falls outside this (e.g. a car still fast right after an oil spill has
     * worn off) is reported as not safe, and [chooseMove] falls back to the heuristic for
     * that turn until the car has slowed back into range.
     */
    private const val SAFE_VELOCITY_CAP = MAX_COMFORT_SPEED
    private const val VELOCITY_RANGE = 2 * SAFE_VELOCITY_CAP + 1

    /**
     * The precomputed safety sets of the last few tracks raced, newest first. A set depends only
     * on track geometry (walls, finish line, subdivision, dimensions), so one computation covers
     * a whole race and a whole tournament heat on the same track.
     *
     * Several entries rather than one, and matched by content rather than by reference: the
     * server races many rooms at once, often on different tracks, and a track decoded from a
     * snapshot or a network message is an equal but separate instance. With a single slot keyed
     * by identity, two rooms on two tracks threw each other's set away every turn. The list is
     * replaced, never mutated, and @Volatile, so concurrent callers can at worst both compute
     * the same set; none can see a wrong-track one.
     */
    @Volatile
    private var cachedSafeSets: List<SafeSet> = emptyList()

    /** How many tracks' safety sets [cachedSafeSets] keeps. */
    private const val SAFE_SET_CACHE_SIZE = 4

    private class SafeSet(
        /** The track this set was computed for; the cache matches it by content. */
        val track: Track,
        /** Grid width the set covers, in coarse units. */
        val width: Int,
        /** Grid height the set covers, in coarse units. */
        val height: Int,
        private val safe: BooleanArray,
    ) {
        /** Whether (position, velocity) is in the winning region; false if either is out of range. */
        fun isSafe(position: Point, velocity: Point): Boolean {
            val vx = velocity.x + SAFE_VELOCITY_CAP
            val vy = velocity.y + SAFE_VELOCITY_CAP
            if (vx < 0 || vx >= VELOCITY_RANGE || vy < 0 || vy >= VELOCITY_RANGE) return false
            if (position.x < 0 || position.x >= width || position.y < 0 || position.y >= height) return false
            return safe[((vy * VELOCITY_RANGE + vx) * height + position.y) * width + position.x]
        }
    }

    private fun safeSetFor(track: Track): SafeSet {
        val cached = cachedSafeSets
        cached.firstOrNull { it.track === track || it.track == track }?.let { return it }
        val computed = computeSafeSet(track)
        cachedSafeSets = (listOf(computed) + cached).take(SAFE_SET_CACHE_SIZE)
        return computed
    }

    /**
     * The set of (position, velocity) states from which the car can avoid crashing into a
     * wall indefinitely, using only moves whose resulting speed is at most
     * [MAX_COMFORT_SPEED] and ignoring modifiers and other cars.
     *
     * The key insight that makes this a single breadth-first search rather than a fixpoint
     * removal: any state at velocity (0,0) is trivially safe, because coasting in place is
     * always a legal mid-race move (a zero-length path crosses no wall), so the car can stay
     * there forever. The safe set is therefore exactly the states that can *reach* some
     * (position, (0,0)) via a chain of wall-free, non-backward-finish-crossing moves - so the
     * BFS is run *backwards* from every (position, (0,0)) seed.
     *
     * A state (p, v) is safe iff some move from it lands in a safe state; the backward BFS
     * discovers exactly those predecessors, so the result is a true winning region, not an
     * approximation. This is not an optimal-path search - it only asks "can I keep moving
     * forever without hitting a wall?", never "which line is shortest" - which is the
     * guarantee [chooseMove] needs to make the AI crash-free on clean track.
     */
    private fun computeSafeSet(track: Track): SafeSet {
        val width = track.width
        val height = track.height
        val safe = BooleanArray(VELOCITY_RANGE * VELOCITY_RANGE * width * height)
        val queue = ArrayDeque<Long>()
        val cap = SAFE_VELOCITY_CAP

        fun encode(px: Int, py: Int, vxOff: Int, vyOff: Int): Long =
            (py.toLong() shl 32) or (px.toLong() shl 16) or (vyOff.toLong() shl 8) or vxOff.toLong()

        fun mark(px: Int, py: Int, vx: Int, vy: Int) {
            val vxOff = vx + cap
            val vyOff = vy + cap
            val idx = ((vyOff * VELOCITY_RANGE + vxOff) * height + py) * width + px
            if (safe[idx]) return
            safe[idx] = true
            queue.addLast(encode(px, py, vxOff, vyOff))
        }

        // Seeds: (position, (0,0)) for every grid point. Wall-interior points are harmless
        // dead seeds - no wall-free path reaches them, so they never propagate into the
        // drivable corridor - and seeding the whole grid avoids a separate reachability pass.
        for (py in 0 until height) {
            for (px in 0 until width) {
                mark(px, py, 0, 0)
            }
        }

        while (queue.isNotEmpty()) {
            val enc = queue.removeFirst()
            val px = ((enc shr 16) and 0xFFFF).toInt()
            val py = ((enc shr 32) and 0xFFFF).toInt()
            val vx = (enc and 0xFF).toInt() - cap
            val vy = ((enc shr 8) and 0xFF).toInt() - cap

            // Successor state is (px, py) @ velocity (vx, vy). A predecessor (p, v) reaches it
            // with a move whose resulting velocity is (vx, vy), so p = successor - velocity.
            // The move path p -> successor is identical for every predecessor velocity, so the
            // wall / backward-finish checks are done once here, outside the delta loop.
            val predX = px - vx
            val predY = py - vy
            if (predX < 0 || predX >= width || predY < 0 || predY >= height) continue
            val predPos = Point(predX, predY)
            val succPos = Point(px, py)
            if (!GameEngine.pathClearOfWalls(track, predPos, succPos)) continue
            if (GameEngine.crossesFinishLineBackward(track, predPos, succPos)) continue

            // Predecessor velocity v = (vx, vy) - delta for delta in {-1,0,1}^2 (the move adds
            // delta to v to get the successor velocity). Keep |v| within the cap so the set
            // stays inside the velocity range the AI will actually query.
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val pvx = vx - dx
                    val pvy = vy - dy
                    if (pvx < -cap || pvx > cap || pvy < -cap || pvy > cap) continue
                    mark(predX, predY, pvx, pvy)
                }
            }
        }

        return SafeSet(track, width, height, safe)
    }

    /**
     * Picks a move for an AI-driven player, by the rules in the class description: a move that
     * finishes the race if there is one, otherwise the best-scoring legal move on safety,
     * progress, hazards and speed - and never a coast to a stop while any other move exists.
     *
     * @param state The race as it stands; [playerId] must be the player whose turn it is.
     * @param playerId The AI player to move.
     * @param random Breaks ties between equally good moves; pass a seeded one for a
     *     reproducible race.
     * @return The chosen target point, or null when the player has no legal move.
     */
    fun chooseMove(state: GameState, playerId: Int, random: Random = Random.Default): Point? {
        val moves = GameEngine.legalMoves(state, playerId).toList()
        if (moves.isEmpty()) return null
        val player = state.players.firstOrNull { it.id == playerId } ?: return null

        // currentPlayerIndex must point at the lone player in this solo copy - applyMove
        // requires state.currentPlayer.id == playerId, and it indexes by position, not id.
        // The probe's own randomness is walled off from the caller's: whether a move finishes
        // the race does not depend on it, so it should not disturb the choice made below.
        val soloState = state.copy(players = listOf(player), currentPlayerIndex = 0)
        val finishingMove = moves.firstOrNull { candidate ->
            GameEngine.applyMove(soloState, playerId, candidate, PROBE_RANDOM).players[0].status == PlayerStatus.FINISHED
        }
        if (finishingMove != null) return finishingMove

        // When the player is unaffected by modifiers, restrict to moves that land in the
        // safety set - states from which the car can avoid crashing into a wall indefinitely
        // (see [computeSafeSet]). This is what makes the AI crash-free on clean track: any
        // move picked here is guaranteed to have a wall-free line forever, so the only way it
        // can crash later is by entering a modifier zone (allowed by design) or by being
        // forced off its line by another car (the engine filters car cells out of `moves`, so
        // absent oil that can't happen either). If no safe move exists from the current state
        // - e.g. the car is already committed too fast into a corner after a spill - fall
        // back to the three-ply heuristic below.
        // The safety set assumes the ordinary rules, so a car the gravel is still braking is
        // treated like one under a hazard and judged by the look-ahead instead. The set itself
        // treats the outer boundary as a wall, so the AI never chooses the gravel when a safe
        // line exists - it only ends up there when nothing safer was left.
        val unaffected = player.pendingModifiers.isEmpty() && player.gravelBrakeTurns == 0
        val safePool = if (unaffected) {
            val safeSet = safeSetFor(state.track)
            moves.filter { candidate ->
                val velocity = candidate - player.position
                speedOf(velocity) <= MAX_COMFORT_SPEED && safeSet.isSafe(candidate, velocity)
            }
        } else {
            emptyList()
        }

        val pool = if (safePool.isNotEmpty()) {
            // Never coast while a safe moving move exists - standing still is safe but makes
            // no progress; only allow it when it's the sole safe option.
            safePool.filterNot { it == player.position }.ifEmpty { safePool }
        } else {
            // Heuristic fallback (also the only path when under a modifier): three-ply safety
            // narrowing, each step giving way rather than emptying the field, then the speed
            // cap. Everything still standing is scored against everything else, so the last
            // word is a trade-off rather than another veto.
            val rolling = moves.filterNot { it == player.position }.ifEmpty { moves }
            val outlooks = rolling.associateWith { outlookFor(state, player, it) }
            val safe = rolling.filter { outlooks.getValue(it).safety == 3 }
                .ifEmpty { rolling.filter { outlooks.getValue(it).safety >= 2 } }
                .ifEmpty { rolling.filter { outlooks.getValue(it).safety >= 1 } }
                .ifEmpty { rolling }
            safe.filter { speedOf(it - player.position) <= MAX_COMFORT_SPEED }.ifEmpty { safe }
        }

        // Tracks are loops around a central obstacle, so treating the track's own bounding-box
        // center as the "ring center" and preferring moves that advance furthest around it
        // reliably drives the AI toward the finish.
        val ringCenter = Point(state.track.width / 2, state.track.height / 2)
        val currentAngle = angleOf(player.position, ringCenter)
        // Forward travel around the ring has a definite rotational direction (clockwise or
        // counterclockwise) that's set by which way the finish line faces - negating
        // finishDirection (a reversed track) flips it. Deriving the sign here instead of
        // always advancing the same way round is what keeps the AI going the right way on
        // reversed tracks instead of looping forever against traffic.
        val forward = travelDirectionSign(state.track, ringCenter)
        val advances = pool.associateWith { candidate ->
            forward * angularAdvance(currentAngle, angleOf(candidate, ringCenter))
        }
        val lowest = advances.values.min()
        val highest = advances.values.max()
        fun progressOf(candidate: Point): Double =
            if (highest - lowest > 1e-12) (advances.getValue(candidate) - lowest) / (highest - lowest) else 0.5

        val scores = pool.associateWith { candidate ->
            val speed = speedOf(candidate - player.position)
            val hazards = GameEngine.modifiersOn(state.track, player.position, candidate).size
            progressOf(candidate) -
                HAZARD_COST * hazards -
                revisitPenalty(player, candidate) +
                SPEED_TIE_BREAK * speed
        }
        val best = scores.values.max()
        val tied = pool.filter { scores.getValue(it) >= best - TIE_EPSILON }
        return tied[random.nextInt(tied.size)]
    }

    private val PROBE_RANDOM = Random(0)

    /** Speed along the faster axis - how many grid units a move covers at its widest. */
    private fun speedOf(velocity: Point): Int = maxOf(abs(velocity.x), abs(velocity.y))

    /**
     * A graduated penalty for moving to a position the player has recently been at: full
     * [REVISIT_PENALTY] for the position 1 move ago, fading to 0 at [RECENT_PATH_WINDOW]
     * moves ago. This is what breaks A-B-A-B oscillation and small loops - the angular
     * progress metric alone can't distinguish forward from backward on radial track sections,
     * so without this the AI can bounce between two equally-scored positions forever.
     *
     * If every candidate is a recent revisit (a tight dead-end), the least-recently-visited
     * one gets the smallest penalty and is still picked, so this never empties the field.
     */
    private fun revisitPenalty(player: PlayerState, candidate: Point): Double {
        val path = player.path
        val windowStart = (path.size - RECENT_PATH_WINDOW).coerceAtLeast(0)
        for (i in windowStart until path.size) {
            if (path[i] == candidate) {
                val movesAgo = path.size - i
                return REVISIT_PENALTY * (1.0 - movesAgo.toDouble() / RECENT_PATH_WINDOW)
            }
        }
        return 0.0
    }

    /**
     * [safety] is how many moves ahead the line stays alive: 3 if the candidate leaves room to
     * move again, that follow-up leaves one, and *that* follow-up leaves one too; 2 if it
     * survives two plies; 1 if only one; 0 if the candidate itself dead-ends.
     */
    private data class Outlook(val safety: Int, val hazards: Int)

    /**
     * Looks three moves ahead, assuming the worst of any hazard picked up on the way.
     *
     * This is the whole of the AI's understanding of hazards, and it is deliberately not a
     * rule about hazards at all. A spill takes the steering away for a turn, so a car that
     * enters one fast is committed to wherever its current velocity carries it; if that is a
     * wall, the look-ahead finds no follow-up and the move scores as the dead end it is. The
     * AI therefore slows before a spill, or takes one on a straight, or goes round - whichever
     * scores best - rather than treating every zone as a wall.
     *
     * The pessimism matters: [PlayerState.oilSlips] is left empty so the follow-up set is the
     * coast point alone. The extra points a real spill offers are drawn only once the car is
     * already in it, so a line planned around getting a favourable one is a line that can turn
     * out to be a crash.
     *
     * The extra ply (vs the old two-ply version) is what lets [MAX_COMFORT_SPEED] rise to 3
     * without a crash-rate regression: a hard-acceleration line that hits a wall three moves
     * in now scores as the dead end it is, where two plies only saw the first two of those
     * moves and could flag the line as safe.
     */
    private fun outlookFor(state: GameState, player: PlayerState, candidate: Point): Outlook {
        val hazards = GameEngine.modifiersOn(state.track, player.position, candidate)
        val next = hypothetical(state, player, candidate, hazards)
        val followUps = GameEngine.legalMoves(next, player.id)
        if (followUps.isEmpty()) return Outlook(safety = 0, hazards = hazards.size)
        val nextPlayer = next.players.first { it.id == player.id }

        // Best safety reachable among the follow-ups, in {0,1,2}. A follow-up that itself
        // dead-ends contributes 0; one that leaves a move but no second move contributes 1;
        // one that leaves two more moves contributes 2. The candidate's own safety is then
        // 1 + that best, giving the 0..3 range documented on [Outlook].
        var bestFollowUpSafety = 0
        for (followUp in followUps) {
            val after = hypothetical(
                next,
                nextPlayer,
                followUp,
                GameEngine.modifiersOn(next.track, nextPlayer.position, followUp),
            )
            val secondFollowUps = GameEngine.legalMoves(after, player.id)
            if (secondFollowUps.isEmpty()) {
                // followUp itself dead-ends -> contributes 0; bestFollowUpSafety stays >= 0.
                continue
            }
            val afterPlayer = after.players.first { it.id == player.id }
            val hasThirdMove = secondFollowUps.any { secondFollowUp ->
                val after2 = hypothetical(
                    after,
                    afterPlayer,
                    secondFollowUp,
                    GameEngine.modifiersOn(after.track, afterPlayer.position, secondFollowUp),
                )
                GameEngine.legalMoves(after2, player.id).isNotEmpty()
            }
            // 2 if this follow-up leaves two more moves, 1 if it leaves one, 0 if it dead-ends.
            val followUpSafety = if (hasThirdMove) 2 else 1
            if (followUpSafety > bestFollowUpSafety) bestFollowUpSafety = followUpSafety
            if (bestFollowUpSafety == 2) break // can't do better; skip the rest of the follow-ups
        }
        return Outlook(safety = 1 + bestFollowUpSafety, hazards = hazards.size)
    }

    /**
     * [state] with [player] moved to [candidate]. `path` is extended as well as `position`,
     * because the engine reads its length to spot a player's opening move - a hypothetical
     * that leaves it short makes every look-ahead from the start line think it still owes a
     * forward finish-line crossing.
     */
    private fun hypothetical(
        state: GameState,
        player: PlayerState,
        candidate: Point,
        hazards: Set<ModifierType>,
    ): GameState {
        val moved = player.copy(
            position = candidate,
            lastVelocity = candidate - player.position,
            path = player.path + candidate,
            pendingModifiers = hazards,
            oilSlips = emptyList(),
            gravelBrakeTurns = MoveRules.nextGravelCounter(
                state.track,
                state.rules,
                player.gravelBrakeTurns,
                player.position,
                candidate,
            ),
        )
        return state.copy(players = state.players.map { if (it.id == player.id) moved else it })
    }

    /**
     * +1 if forward travel increases the angle around [ringCenter] (the default the old AI
     * assumed), -1 if it decreases it. The sign is the cross product of (finish-line midpoint
     * relative to the ring center) and [Track.finishDirection]: d(angle)/dt has the same sign
     * as that cross product, so a finish line facing the other way (a reversed track) flips it.
     */
    private fun travelDirectionSign(track: Track, ringCenter: Point): Int {
        val midFineX = (track.finishLine.from.x + track.finishLine.to.x) / 2.0
        val midFineY = (track.finishLine.from.y + track.finishLine.to.y) / 2.0
        val midCoarseX = midFineX / track.subdivision
        val midCoarseY = midFineY / track.subdivision
        val cross = (midCoarseX - ringCenter.x) * track.finishDirection.y -
            (midCoarseY - ringCenter.y) * track.finishDirection.x
        return if (cross >= 0) 1 else -1
    }

    private fun angleOf(p: Point, center: Point): Double =
        atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())

    /** How far [to] is ahead of [from] going the same way round, wrapped to (-pi, pi]. */
    private fun angularAdvance(from: Double, to: Double): Double {
        var delta = to - from
        while (delta <= -PI) delta += 2 * PI
        while (delta > PI) delta -= 2 * PI
        return delta
    }
}
